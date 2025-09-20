package com.limelight;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.TrafficStats;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;

import java.util.Calendar;
import java.util.TimeZone;
import java.text.SimpleDateFormat;
import java.util.Locale;

import com.limelight.binding.video.PerformanceInfo;
import com.limelight.preferences.PerfOverlayDisplayItemsPreference;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamView;
import com.limelight.utils.NetHelper;
import com.limelight.utils.MoonPhaseUtils;

/**
 * 性能覆盖层管理器
 * 负责性能覆盖层的显示、隐藏、配置、拖动和位置管理。
 * 保留了原有注释与行为。
 */
public class PerformanceOverlayManager {

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;

    private LinearLayout performanceOverlayView;
    private StreamView streamView;

    private TextView perfResView;
    private TextView perfDecoderView;
    private TextView perfRenderFpsView;
    private TextView networkLatencyView;
    private TextView decodeLatencyView;
    private TextView hostLatencyView;
    private TextView packetLossView;

    private int requestedPerformanceOverlayVisibility = View.GONE;
    private boolean hasShownPerfOverlay = false; // 跟踪性能覆盖层是否已经显示过

    // 性能覆盖层拖动相关
    private boolean isDraggingPerfOverlay = false;
    private float perfOverlayStartX, perfOverlayStartY;
    private float perfOverlayDeltaX, perfOverlayDeltaY;
    private static final int SNAP_THRESHOLD = 100; // 吸附阈值（像素）
    
    // 点击检测相关
    private static final int CLICK_THRESHOLD = 10; // 点击阈值（像素）
    private static final int DOUBLE_CLICK_TIMEOUT = 300; // 双击超时时间（毫秒）
    private long clickStartTime = 0;
    private float clickStartX, clickStartY; // 记录点击开始位置
    private long lastClickTime = 0; // 上次点击时间
    private boolean isDoubleClick = false; // 是否为双击

    // 8个吸附位置的枚举
    private enum SnapPosition {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    // 计算带宽用
    private long previousTimeMillis = 0;
    private long previousRxBytes = 0;
    private String lastValidBandwidth = "N/A";
    
    // 月相缓存
    private String currentMoonPhaseIcon = "🌙";
    private int lastCalculatedDay = -1;

    public PerformanceOverlayManager(Activity activity, PreferenceConfiguration prefConfig) {
        this.activity = activity;
        this.prefConfig = prefConfig;
    }

    /**
     * 初始化性能覆盖层
     */
    public void initialize() {
        performanceOverlayView = activity.findViewById(R.id.performanceOverlay);
        streamView = activity.findViewById(R.id.surfaceView);

        perfResView = activity.findViewById(R.id.perfRes);
        perfDecoderView = activity.findViewById(R.id.perfDecoder);
        perfRenderFpsView = activity.findViewById(R.id.perfRenderFps);
        networkLatencyView = activity.findViewById(R.id.perfNetworkLatency);
        decodeLatencyView = activity.findViewById(R.id.perfDecodeLatency);
        hostLatencyView = activity.findViewById(R.id.perfHostLatency);
        packetLossView = activity.findViewById(R.id.perfPacketLoss);

        // 加载保存的布局方向设置
        loadLayoutOrientation();

        // Check if the user has enabled performance stats overlay
        if (prefConfig.enablePerfOverlay) {
            requestedPerformanceOverlayVisibility = View.VISIBLE;
            // 初始状态下设置为不可见，等待性能数据更新时再显示
            if (performanceOverlayView != null) {
                performanceOverlayView.setVisibility(View.GONE);
                performanceOverlayView.setAlpha(0.0f);
            }
            // 配置性能覆盖层的方向和位置
            configurePerformanceOverlay();
        }
    }

    /** 隐藏覆盖层（立即） */
    public void hideOverlayImmediate() {
        if (performanceOverlayView != null) {
            performanceOverlayView.setVisibility(View.GONE);
        }
    }

    /** 应用当前请求的可见性到视图 */
    public void applyRequestedVisibility() {
        if (performanceOverlayView != null) {
            performanceOverlayView.setVisibility(requestedPerformanceOverlayVisibility);
        }
    }

    /** 覆盖层是否可见 */
    public boolean isPerfOverlayVisible() {
        return requestedPerformanceOverlayVisibility == View.VISIBLE;
    }

    /** 切换覆盖层显示/隐藏 */
    public void togglePerformanceOverlay() {
        if (performanceOverlayView == null) {
            return;
        }

        if (requestedPerformanceOverlayVisibility == View.VISIBLE) {
            // 隐藏性能覆盖层 - 使用淡出动画
            requestedPerformanceOverlayVisibility = View.GONE;
            hasShownPerfOverlay = false; // 重置显示状态
            Animation fadeOutAnimation = AnimationUtils.loadAnimation(activity, R.anim.perf_overlay_fadeout);
            performanceOverlayView.startAnimation(fadeOutAnimation);
            fadeOutAnimation.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {}

                @Override
                public void onAnimationEnd(Animation animation) {
                    performanceOverlayView.setVisibility(View.GONE);
                    performanceOverlayView.setAlpha(0.0f);
                }

                @Override
                public void onAnimationRepeat(Animation animation) {}
            });
        } else {
            requestedPerformanceOverlayVisibility = View.VISIBLE;
            hasShownPerfOverlay = true; // 标记为已显示，避免重复动画
            performanceOverlayView.setVisibility(View.VISIBLE);
            performanceOverlayView.setAlpha(1.0f);
        }
    }

    /** 刷新覆盖层配置（显示项与对齐） */
    public void refreshPerformanceOverlayConfig() {
        if (performanceOverlayView != null && requestedPerformanceOverlayVisibility == View.VISIBLE) {
            configureDisplayItems();
            configureTextAlignment();
        }
    }

    /**
     * 更新性能信息（带宽、丢包、延迟等）并刷新文案
     */
    public void updatePerformanceInfo(final PerformanceInfo performanceInfo) {
        // 计算带宽信息
        updateBandwidthInfo(performanceInfo);

        // 在UI线程中更新显示
        activity.runOnUiThread(() -> {
            showOverlayIfNeeded();
            updatePerformanceViewsWithStyledText(performanceInfo);
        });
    }

    /**
     * 更新带宽信息
     */
    private void updateBandwidthInfo(PerformanceInfo performanceInfo) {
        long currentRxBytes = TrafficStats.getTotalRxBytes();
        long timeMillis = System.currentTimeMillis();
        long timeMillisInterval = timeMillis - previousTimeMillis;

        String calculatedBandwidth = NetHelper.calculateBandwidth(currentRxBytes, previousRxBytes, timeMillisInterval);
        
        // 如果时间间隔过长，使用上次有效带宽
        if (timeMillisInterval > 5000) {
            performanceInfo.bandWidth = lastValidBandwidth != null ? lastValidBandwidth : "N/A";
            previousTimeMillis = timeMillis;
            previousRxBytes = currentRxBytes;
            return;
        }

        // 检查计算出的带宽是否可靠
        if (!calculatedBandwidth.equals("0 K/s")) {
            performanceInfo.bandWidth = calculatedBandwidth;
            lastValidBandwidth = calculatedBandwidth;
            // 只有带宽数据可靠时才更新时间戳
            previousTimeMillis = timeMillis;
        } else {
            // 带宽数据不可靠，使用上次有效值
            performanceInfo.bandWidth = lastValidBandwidth != null ? lastValidBandwidth : "N/A";
        }

        // 无论带宽数据是否可靠，都要更新 previousRxBytes 用于下次计算
        previousRxBytes = currentRxBytes;
    }

    /**
     * 构建解码器信息字符串
     */
    private String buildDecoderInfo(PerformanceInfo performanceInfo) {
        String decoderInfo = performanceInfo.decoder.replaceFirst(".*\\.(avc|hevc|av1).*", "$1").toUpperCase();
        // 基于实际HDR激活状态而不是配置
        if (performanceInfo.isHdrActive) {
            decoderInfo += " HDR";
        }
        return decoderInfo;
    }

    /**
     * 获取当前月相图标
     * 基于真实的天文月相计算，带缓存优化
     */
    private String getCurrentMoonPhaseIcon() {
        Calendar now = Calendar.getInstance(TimeZone.getDefault());
        int currentDay = now.get(Calendar.DAY_OF_YEAR);
        
        // 如果是同一天，使用缓存的图标
        if (currentDay == lastCalculatedDay) {
            return currentMoonPhaseIcon;
        }
        
        // 计算月相
        currentMoonPhaseIcon = MoonPhaseUtils.getMoonPhaseIcon(MoonPhaseUtils.getCurrentMoonPhase());
        lastCalculatedDay = currentDay;
        
        return currentMoonPhaseIcon;
    }


    /**
     * 如果需要则显示覆盖层
     */
    private void showOverlayIfNeeded() {
        if (!hasShownPerfOverlay && requestedPerformanceOverlayVisibility == View.VISIBLE && performanceOverlayView != null) {
            performanceOverlayView.setVisibility(View.VISIBLE);
            performanceOverlayView.setAlpha(1.0f);
            hasShownPerfOverlay = true;
        }
    }

    /**
     * 创建带有优雅字体样式的SpannableString
     * @param icon 图标或前缀
     * @param value 主要数值
     * @param unit 单位或后缀
     * @param valueColor 数值颜色（可选）
     * @return 带样式的SpannableString
     */
    private SpannableString createStyledText(String icon, String value, String unit, Integer valueColor) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        
        // 添加图标（使用标题样式）
        if (icon != null && !icon.isEmpty()) {
            int iconStart = builder.length();
            builder.append(icon);
            builder.setSpan(new StyleSpan(Typeface.BOLD), iconStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new RelativeSizeSpan(1.1f), iconStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.append(" ");
        }
        
        // 添加数值（使用中等粗细样式）
        if (value != null && !value.isEmpty()) {
            int valueStart = builder.length();
            builder.append(value);
            builder.setSpan(new TypefaceSpan("sans-serif-medium"), valueStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new RelativeSizeSpan(1.0f), valueStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (valueColor != null) {
                builder.setSpan(new ForegroundColorSpan(valueColor), valueStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        
        // 添加单位（使用细体样式）
        if (unit != null && !unit.isEmpty()) {
            builder.append(" ");
            int unitStart = builder.length();
            builder.append(unit);
            builder.setSpan(new TypefaceSpan("sans-serif-light"), unitStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new RelativeSizeSpan(0.9f), unitStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new ForegroundColorSpan(0xCCFFFFFF), unitStart, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        
        return new SpannableString(builder);
    }

    /**
     * 更新所有性能视图（使用优雅的字体样式）
     */
    private void updatePerformanceViewsWithStyledText(PerformanceInfo performanceInfo) {
        // 更新分辨率信息
        if (perfResView != null && perfResView.getVisibility() == View.VISIBLE) {
            @SuppressLint("DefaultLocale") String resValue = String.format("%dx%d@%.0f",
                performanceInfo.initialWidth, performanceInfo.initialHeight, performanceInfo.totalFps);
            String moonIcon = getCurrentMoonPhaseIcon();
            perfResView.setText(createStyledText(moonIcon, resValue, "", null));
        }
        
        // 更新解码器信息
        if (perfDecoderView != null && perfDecoderView.getVisibility() == View.VISIBLE) {
            String decoderInfo = buildDecoderInfo(performanceInfo);
            perfDecoderView.setText(createStyledText("", decoderInfo, "", null));
            perfDecoderView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        }
        
        // 更新渲染FPS信息
        if (perfRenderFpsView != null && perfRenderFpsView.getVisibility() == View.VISIBLE) {
            @SuppressLint("DefaultLocale") String fpsValue = String.format("Rx %.0f / Rd %.0f", performanceInfo.receivedFps, performanceInfo.renderedFps);
            perfRenderFpsView.setText(createStyledText("", fpsValue, "FPS", 0xFF0DDAF4));
        }
        
        // 更新丢包率信息
        if (packetLossView != null && packetLossView.getVisibility() == View.VISIBLE) {
            @SuppressLint("DefaultLocale") String lossValue = String.format("%.2f", performanceInfo.lostFrameRate);
            int lossColor = performanceInfo.lostFrameRate < 5.0f ? 0xFF7D9D7D : 0xFFB57D7D;
            packetLossView.setText(createStyledText("📶", lossValue, "%", lossColor));
        }
        
        // 更新网络延迟信息
        if (networkLatencyView != null && networkLatencyView.getVisibility() == View.VISIBLE) {
            boolean showPacketLoss = packetLossView != null && packetLossView.getVisibility() == View.VISIBLE;
            String icon = showPacketLoss ? "" : "🌐";
            @SuppressLint("DefaultLocale") String bandwidthAndLatency = String.format("%s   %d ± %d",
                performanceInfo.bandWidth,
                (int) (performanceInfo.rttInfo >> 32),
                (int) performanceInfo.rttInfo);
            networkLatencyView.setText(createStyledText(icon, bandwidthAndLatency, "ms", 0xFFBCEDD3));
        }
        
        // 更新解码延迟信息
        if (decodeLatencyView != null && decodeLatencyView.getVisibility() == View.VISIBLE) {
            String icon = performanceInfo.decodeTimeMs < 15 ? "⏱️" : "🥵";
            @SuppressLint("DefaultLocale") String latencyValue = String.format("%.2f", performanceInfo.decodeTimeMs);
            decodeLatencyView.setText(createStyledText(icon, latencyValue, "ms", 0xFFD597E3));
        }
        
        // 更新主机延迟信息
        if (hostLatencyView != null && hostLatencyView.getVisibility() == View.VISIBLE) {
            if (performanceInfo.framesWithHostProcessingLatency > 0) {
                @SuppressLint("DefaultLocale") String latencyValue = String.format("%.1f", performanceInfo.aveHostProcessingLatency);
                hostLatencyView.setText(createStyledText("🖥", latencyValue, "ms", 0xFF009688));
            } else {
                hostLatencyView.setText(createStyledText("🧋", "Ver.V+", "", 0xFF009688));
            }
        }
        
        // 确保文字对齐方式得到正确应用
        configureTextAlignment();
    }

    private void configurePerformanceOverlay() {
        if (performanceOverlayView == null) {
            return;
        }

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) performanceOverlayView.getLayoutParams();

        // 设置方向
        if (prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL) {
            performanceOverlayView.setOrientation(LinearLayout.VERTICAL);
            performanceOverlayView.setBackgroundColor(activity.getResources().getColor(R.color.overlay_background_vertical));
        } else {
            performanceOverlayView.setOrientation(LinearLayout.HORIZONTAL);
            performanceOverlayView.setBackgroundColor(activity.getResources().getColor(R.color.overlay_background_horizontal));
        }

        // 根据用户配置显示/隐藏特定的性能指标
        configureDisplayItems();

        // 从SharedPreferences读取保存的位置
        SharedPreferences prefs = activity.getSharedPreferences("performance_overlay", Activity.MODE_PRIVATE);
        boolean hasCustomPosition = prefs.getBoolean("has_custom_position", false);

        if (hasCustomPosition) {
            // 使用自定义位置
            layoutParams.gravity = Gravity.NO_GRAVITY;
            layoutParams.leftMargin = prefs.getInt("left_margin", 0);
            layoutParams.topMargin = prefs.getInt("top_margin", 0);
        } else {
            // 使用预设位置
            switch (prefConfig.perfOverlayPosition) {
                case TOP:
                    layoutParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
                    break;
                case BOTTOM:
                    layoutParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
                    break;
                case TOP_LEFT:
                    layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
                    break;
                case TOP_RIGHT:
                    layoutParams.gravity = Gravity.RIGHT | Gravity.TOP;
                    break;
                case BOTTOM_LEFT:
                    layoutParams.gravity = Gravity.LEFT | Gravity.BOTTOM;
                    break;
                case BOTTOM_RIGHT:
                    layoutParams.gravity = Gravity.RIGHT | Gravity.BOTTOM;
                    break;
                default:
                    layoutParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
                    break;
            }
            // 清除自定义边距
            layoutParams.leftMargin = 0;
            layoutParams.topMargin = 0;
        }
            layoutParams.rightMargin = 0;
            layoutParams.bottomMargin = 0;

        performanceOverlayView.setLayoutParams(layoutParams);

        // 根据位置和方向调整文字对齐（延迟执行确保View已测量）
        performanceOverlayView.post(this::configureTextAlignment);

        // 设置拖动监听器
        setupPerformanceOverlayDragging();
    }

    private void configureDisplayItems() {
        // 根据用户配置显示/隐藏特定的性能指标
        if (perfResView != null) {
            perfResView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "resolution") ?
                View.VISIBLE : View.GONE);
        }
        if (perfDecoderView != null) {
            perfDecoderView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "decoder") ?
                View.VISIBLE : View.GONE);
        }
        if (perfRenderFpsView != null) {
            perfRenderFpsView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "render_fps") ?
                View.VISIBLE : View.GONE);
        }
        if (networkLatencyView != null) {
            networkLatencyView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "network_latency") ?
                View.VISIBLE : View.GONE);
        }
        if (decodeLatencyView != null) {
            decodeLatencyView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "decode_latency") ?
                View.VISIBLE : View.GONE);
        }
        if (hostLatencyView != null) {
            hostLatencyView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "host_latency") ?
                View.VISIBLE : View.GONE);
        }
        if (packetLossView != null) {
            packetLossView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(activity, "packet_loss") ?
                    View.VISIBLE : View.GONE);
        }
    }

    private void configureTextAlignment() {
        if (performanceOverlayView == null) {
            return;
        }

        boolean isVertical = prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL;
        boolean isRightSide = determineRightSidePosition(isVertical);

        // 只在垂直布局且位置在右侧时，将文字设置为右对齐
        // 注意：需要保持 center_vertical 以确保文字垂直居中
        int gravity = (isVertical && isRightSide) ? 
            (android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END) : 
            (android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);

        // 批量设置所有性能信息文本的对齐方式和阴影效果
        TextView[] perfViews = {
            perfResView, perfDecoderView, perfRenderFpsView,
                networkLatencyView, decodeLatencyView, hostLatencyView, packetLossView
        };

        for (TextView textView : perfViews) {
            if (textView != null && textView.getVisibility() == View.VISIBLE) {
                configureTextViewStyle(textView, gravity, isVertical);
            }
        }
    }

    /**
     * 判断性能覆盖层是否位于右侧
     */
    private boolean determineRightSidePosition(boolean isVertical) {
        SharedPreferences prefs = activity.getSharedPreferences("performance_overlay", Activity.MODE_PRIVATE);
        boolean hasCustomPosition = prefs.getBoolean("has_custom_position", false);

        if (hasCustomPosition) {
            // 自定义位置：检查是否接近右侧
            int[] viewDimensions = getViewDimensions(performanceOverlayView);
            int viewWidth = viewDimensions[0];
            int leftMargin = prefs.getInt("left_margin", 0);

            // 如果距离右边缘小于屏幕宽度的1/3，认为是右侧
            return (leftMargin + viewWidth) > (streamView.getWidth() * 2 / 3);
        } else {
            // 预设位置：检查是否为右侧位置
            return prefConfig.perfOverlayPosition == PreferenceConfiguration.PerfOverlayPosition.TOP_RIGHT ||
                   prefConfig.perfOverlayPosition == PreferenceConfiguration.PerfOverlayPosition.BOTTOM_RIGHT;
        }
    }

    /**
     * 配置单个TextView的样式（对齐方式、阴影效果和字体）
     */
    private void configureTextViewStyle(TextView textView, int gravity, boolean isVertical) {
        // 设置文字对齐方式
        textView.setGravity(gravity);

        // 设置基础字体属性
        textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        textView.setLetterSpacing(0.02f);
        textView.setIncludeFontPadding(false);

        // 根据布局方向设置阴影效果
        if (isVertical) {
            // 竖屏时添加字体阴影，提高可读性
            textView.setShadowLayer(2.5f, 1.0f, 1.0f, 0x80000000);
        } else {
            // 横屏时使用较轻的阴影
            textView.setShadowLayer(1.5f, 0.5f, 0.5f, 0x60000000);
        }

        // 根据TextView的ID设置特定的字体样式
        int viewId = textView.getId();
        if (viewId == R.id.perfRes) {
            // 分辨率信息 - 标题样式
            textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            textView.setTextSize(11);
        } else if (viewId == R.id.perfDecoder) {
            // 解码器信息 - 强调样式
            textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            textView.setTextSize(10);
        } else if (viewId == R.id.perfRenderFps) {
            // FPS信息 - 数值样式
            textView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textView.setTextSize(10);
        } else if (viewId == R.id.perfPacketLoss) {
            // 丢包率 - 状态样式
            textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textView.setTextSize(10);
        } else if (viewId == R.id.perfNetworkLatency) {
            // 网络延迟 - 状态样式
            textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textView.setTextSize(10);
        } else if (viewId == R.id.perfDecodeLatency) {
            // 解码延迟 - 状态样式
            textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textView.setTextSize(10);
        } else if (viewId == R.id.perfHostLatency) {
            // 主机延迟 - 状态样式
            textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textView.setTextSize(10);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupPerformanceOverlayDragging() {
        if (performanceOverlayView == null) {
            return;
        }

        performanceOverlayView.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        isDraggingPerfOverlay = true;
                        perfOverlayStartX = event.getRawX();
                        perfOverlayStartY = event.getRawY();
                    clickStartTime = System.currentTimeMillis();
                    // 记录点击位置，用于判断点击的是哪个项目
                    clickStartX = event.getX();
                    clickStartY = event.getY();
                        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) v.getLayoutParams();

                        // 如果使用预设位置（gravity不为NO_GRAVITY），需要转换为实际坐标
                        if (layoutParams.gravity != Gravity.NO_GRAVITY) {
                            int[] viewLocation = new int[2];
                            int[] parentLocation = new int[2];
                            v.getLocationInWindow(viewLocation);
                            ((View)v.getParent()).getLocationInWindow(parentLocation);

                            // 将预设位置转换为相对于父容器的leftMargin和topMargin
                            layoutParams.leftMargin = viewLocation[0] - parentLocation[0];
                            layoutParams.topMargin = viewLocation[1] - parentLocation[1];
                            layoutParams.gravity = Gravity.NO_GRAVITY;
                            v.setLayoutParams(layoutParams);
                        }

                        perfOverlayDeltaX = perfOverlayStartX - layoutParams.leftMargin;
                        perfOverlayDeltaY = perfOverlayStartY - layoutParams.topMargin;

                        // 添加视觉反馈：降低透明度表示正在拖动
                        v.setAlpha(0.7f);
                        v.setScaleX(1.05f);
                        v.setScaleY(1.05f);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (isDraggingPerfOverlay) {
                            // 获取父容器和View的尺寸
                            int[] parentDimensions = getParentDimensions(v);
                            int[] viewDimensions = getViewDimensions(v);
                            int parentWidth = parentDimensions[0];
                            int parentHeight = parentDimensions[1];
                            int viewWidth = viewDimensions[0];
                            int viewHeight = viewDimensions[1];

                            layoutParams = (FrameLayout.LayoutParams) v.getLayoutParams();
                            int newLeftMargin = (int) (event.getRawX() - perfOverlayDeltaX);
                            int newTopMargin = (int) (event.getRawY() - perfOverlayDeltaY);

                            // 边界检查，防止移出屏幕
                            newLeftMargin = Math.max(0, Math.min(newLeftMargin, parentWidth - viewWidth));
                            newTopMargin = Math.max(0, Math.min(newTopMargin, parentHeight - viewHeight));

                            layoutParams.leftMargin = newLeftMargin;
                            layoutParams.topMargin = newTopMargin;
                            layoutParams.gravity = Gravity.NO_GRAVITY;
                            v.setLayoutParams(layoutParams);

                            // 拖动过程中实时更新文字对齐
                            configureTextAlignment();
                            return true;
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        if (isDraggingPerfOverlay) {
                            isDraggingPerfOverlay = false;

                            // 恢复视觉效果
                            v.setAlpha(1.0f);
                            v.setScaleX(1.0f);
                            v.setScaleY(1.0f);

                        // 检测是否为点击事件
                        if (isClick(event)) {
                            handlePerformanceOverlayClick(v);
                        } else {
                            snapToNearestPosition(v);
                        }

                            return true;
                        }
                        break;
                }
                return false;
        });
    }

    /**
     * 点击项目枚举
     */
    private enum ClickedItem {
        RESOLUTION,     // 分辨率信息
        DECODER,        // 解码器信息
        FPS,            // FPS信息
        PACKET_LOSS,    // 丢包率信息
        NETWORK_LATENCY, // 网络延迟信息
        DECODE_LATENCY,  // 解码延迟信息
        HOST_LATENCY,   // 主机延迟信息
        NONE            // 点击空白区域
    }

    /**
     * 处理性能覆盖层点击事件
     * 根据点击位置显示对应的详细信息
     */
    private void handlePerformanceOverlayClick(View overlayView) {
        // 如果是双击，切换布局方向
        if (isDoubleClick) {
            toggleLayoutOrientation();
            return;
        }
        
        // 判断点击的是哪个项目
        ClickedItem clickedItem = getClickedItem(overlayView);
        
        switch (clickedItem) {
            case RESOLUTION:
                showMoonPhaseInfo();
                break;
            case DECODER:
                showDecoderInfo();
                break;
            case FPS:
                showFpsInfo();
                break;
            case PACKET_LOSS:
                showPacketLossInfo();
                break;
            case NETWORK_LATENCY:
                showNetworkLatencyInfo();
                break;
            case DECODE_LATENCY:
                showDecodeLatencyInfo();
                break;
            case HOST_LATENCY:
                showHostLatencyInfo();
                break;
            default:
                // 点击空白区域，显示月相信息作为默认
                showMoonPhaseInfo();
                break;
        }
    }

    /**
     * 判断点击的是哪个项目
     */
    private ClickedItem getClickedItem(View overlayView) {
        // 获取覆盖层位置
        int[] overlayLocation = new int[2];
        overlayView.getLocationInWindow(overlayLocation);
        
        // 检查每个可见的TextView
        TextView[] textViews = {perfResView, perfDecoderView, perfRenderFpsView, 
                               packetLossView, networkLatencyView, decodeLatencyView, hostLatencyView};
        ClickedItem[] items = {ClickedItem.RESOLUTION, ClickedItem.DECODER, ClickedItem.FPS,
                              ClickedItem.PACKET_LOSS, ClickedItem.NETWORK_LATENCY, 
                              ClickedItem.DECODE_LATENCY, ClickedItem.HOST_LATENCY};
        
        for (int i = 0; i < textViews.length; i++) {
            TextView textView = textViews[i];
            if (textView != null && textView.getVisibility() == View.VISIBLE) {
                if (isClickInTextView(textView, overlayLocation)) {
                    return items[i];
                }
            }
        }
        
        return ClickedItem.NONE;
    }

    /**
     * 判断点击位置是否在指定TextView内
     */
    private boolean isClickInTextView(TextView textView, int[] overlayLocation) {
        int[] textViewLocation = new int[2];
        textView.getLocationInWindow(textViewLocation);
        
        // 计算相对于覆盖层的坐标
        int relativeX = textViewLocation[0] - overlayLocation[0];
        int relativeY = textViewLocation[1] - overlayLocation[1];
        
        int textViewWidth = textView.getWidth();
        int textViewHeight = textView.getHeight();
        
        // 判断点击位置是否在TextView范围内
        boolean isInXRange = clickStartX >= relativeX && clickStartX <= (relativeX + textViewWidth);
        boolean isInYRange = clickStartY >= relativeY && clickStartY <= (relativeY + textViewHeight);
        
        return isInXRange && isInYRange;
    }

    /**
     * 显示月相信息对话框
     */
    private void showMoonPhaseInfo() {
        MoonPhaseUtils.MoonPhaseInfo moonPhaseInfo = MoonPhaseUtils.getCurrentMoonPhaseInfo();
        
        // 计算月相百分比和天数
        double moonPhase = MoonPhaseUtils.getCurrentMoonPhase();
        double phasePercentage = MoonPhaseUtils.getMoonPhasePercentage(moonPhase);
        int daysInCycle = MoonPhaseUtils.getDaysInMoonCycle(moonPhase);
        
        // 格式化日期
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault());
        String currentDate = dateFormat.format(Calendar.getInstance(TimeZone.getDefault()).getTime());
        
        // 创建月相信息文本
        String moonInfo = String.format(
            activity.getString(R.string.perf_moon_phase_info),
            moonPhaseInfo.icon, moonPhaseInfo.name, phasePercentage, daysInCycle, currentDate, moonPhaseInfo.description
        );
        
        // 显示对话框
        showMoonPhaseDialog(moonInfo);
    }

    /**
     * 显示月相信息对话框
     */
    private void showMoonPhaseDialog(String message) {
        MoonPhaseUtils.MoonPhaseInfo moonInfo = MoonPhaseUtils.getCurrentMoonPhaseInfo();
        
        new AlertDialog.Builder(activity, R.style.AppDialogStyle)
            .setTitle(moonInfo.poeticTitle)
            .setMessage(message)
            .setPositiveButton("Ok", null)
            .setCancelable(true)
            .show();
    }


    /**
     * 显示解码器信息
     */
    private void showDecoderInfo() {
        showInfoDialog(
            activity.getString(R.string.perf_decoder_title),
            activity.getString(R.string.perf_decoder_info)
        );
    }

    /**
     * 显示FPS信息
     */
    private void showFpsInfo() {
        showInfoDialog(
            activity.getString(R.string.perf_fps_title),
            activity.getString(R.string.perf_fps_info)
        );
    }

    /**
     * 显示丢包率信息
     */
    private void showPacketLossInfo() {
        showInfoDialog(
            activity.getString(R.string.perf_packet_loss_title),
            activity.getString(R.string.perf_packet_loss_info)
        );
    }

    /**
     * 显示网络延迟信息
     */
    private void showNetworkLatencyInfo() {
        showInfoDialog(
            activity.getString(R.string.perf_network_latency_title),
            activity.getString(R.string.perf_network_latency_info)
        );
    }

    /**
     * 显示解码延迟信息
     */
    private void showDecodeLatencyInfo() {
        showInfoDialog(
            activity.getString(R.string.perf_decode_latency_title),
            activity.getString(R.string.perf_decode_latency_info)
        );
    }

    /**
     * 显示主机延迟信息
     */
    private void showHostLatencyInfo() {
        showInfoDialog(
            activity.getString(R.string.perf_host_latency_title),
            activity.getString(R.string.perf_host_latency_info)
        );
    }

    /**
     * 显示信息对话框
     */
    private void showInfoDialog(String title, String message) {
        new AlertDialog.Builder(activity, R.style.AppDialogStyle)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.yes), null)
            .setCancelable(true)
            .show();
    }

    /**
     * 切换性能覆盖层布局方向
     */
    private void toggleLayoutOrientation() {
        // 切换布局方向
        if (prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL) {
            prefConfig.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.HORIZONTAL;
        } else {
            prefConfig.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.VERTICAL;
        }
        
        // 保存设置到SharedPreferences
        saveLayoutOrientation();
        
        // 重新配置性能覆盖层
        configurePerformanceOverlay();
    }

    /**
     * 保存布局方向设置
     */
    private void saveLayoutOrientation() {
        SharedPreferences prefs = activity.getSharedPreferences("performance_overlay", Activity.MODE_PRIVATE);
        prefs.edit()
            .putString("layout_orientation", prefConfig.perfOverlayOrientation.name())
            .apply();
    }

    /**
     * 加载保存的布局方向设置
     */
    private void loadLayoutOrientation() {
        SharedPreferences prefs = activity.getSharedPreferences("performance_overlay", Activity.MODE_PRIVATE);
        String savedOrientation = prefs.getString("layout_orientation", null);
        
        if (savedOrientation != null) {
            try {
                prefConfig.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.valueOf(savedOrientation);
            } catch (IllegalArgumentException e) {
                // 如果保存的值无效，使用默认值
                prefConfig.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.VERTICAL;
            }
        }
    }

    /**
     * 检测是否为点击事件（而非拖动）
     */
    private boolean isClick(MotionEvent event) {
        float deltaX = Math.abs(event.getRawX() - perfOverlayStartX);
        float deltaY = Math.abs(event.getRawY() - perfOverlayStartY);
        long deltaTime = System.currentTimeMillis() - clickStartTime;
        
        // 点击条件：移动距离小且时间短
        boolean isClick = deltaX < CLICK_THRESHOLD && deltaY < CLICK_THRESHOLD && deltaTime < 500;
        
        if (isClick) {
            // 检查是否为双击
            long currentTime = System.currentTimeMillis();
            long timeSinceLastClick = currentTime - lastClickTime;
            
            if (timeSinceLastClick < DOUBLE_CLICK_TIMEOUT) {
                isDoubleClick = true;
                lastClickTime = 0; // 重置，避免连续三次点击被识别为双击
            } else {
                isDoubleClick = false;
                lastClickTime = currentTime;
            }
        }
        
        return isClick;
    }

    private void snapToNearestPosition(View view) {
        // 获取父容器和View的尺寸
        int[] parentDimensions = getParentDimensions(view);
        int[] viewDimensions = getViewDimensions(view);
        int screenWidth = parentDimensions[0];
        int screenHeight = parentDimensions[1];
        int viewWidth = viewDimensions[0];
        int viewHeight = viewDimensions[1];

        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) view.getLayoutParams();
        int currentX = layoutParams.leftMargin + viewWidth / 2;
        int currentY = layoutParams.topMargin + viewHeight / 2;

        // 计算到各个吸附位置的距离
        SnapPosition nearestPosition = SnapPosition.TOP_CENTER;
        double minDistance = Double.MAX_VALUE;

        // 定义8个吸附位置
        int[][] snapPositions = {
            {viewWidth / 2, viewHeight / 2}, // TOP_LEFT
            {screenWidth / 2, viewHeight / 2}, // TOP_CENTER
            {screenWidth - viewWidth / 2, viewHeight / 2}, // TOP_RIGHT
            {viewWidth / 2, screenHeight / 2}, // CENTER_LEFT
            {screenWidth - viewWidth / 2, screenHeight / 2}, // CENTER_RIGHT
            {viewWidth / 2, screenHeight - viewHeight / 2}, // BOTTOM_LEFT
            {screenWidth / 2, screenHeight - viewHeight / 2}, // BOTTOM_CENTER
            {screenWidth - viewWidth / 2, screenHeight - viewHeight / 2} // BOTTOM_RIGHT
        };

        SnapPosition[] positions = SnapPosition.values();

        // 找到最近的吸附位置
        for (int i = 0; i < snapPositions.length; i++) {
            double distance = Math.sqrt(
                Math.pow(currentX - snapPositions[i][0], 2) +
                Math.pow(currentY - snapPositions[i][1], 2)
            );

            if (distance < minDistance) {
                minDistance = distance;
                nearestPosition = positions[i];
            }
        }

        // 吸过来
        animateToSnapPosition(view, nearestPosition, screenWidth, screenHeight);
    }

    private void animateToSnapPosition(View view, SnapPosition position, int screenWidth, int screenHeight) {
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) view.getLayoutParams();
        int[] viewDimensions = getViewDimensions(view);
        int viewWidth = viewDimensions[0];
        int viewHeight = viewDimensions[1];

        int targetX, targetY;

        switch (position) {
            case TOP_LEFT:
                targetX = 0;
                targetY = 0;
                break;
            case TOP_CENTER:
                targetX = (screenWidth - viewWidth) / 2;
                targetY = 0;
                break;
            case TOP_RIGHT:
                targetX = screenWidth - viewWidth;
                targetY = 0;
                break;
            case CENTER_LEFT:
                targetX = 0;
                targetY = (screenHeight - viewHeight) / 2;
                break;
            case CENTER_RIGHT:
                targetX = screenWidth - viewWidth;
                targetY = (screenHeight - viewHeight) / 2;
                break;
            case BOTTOM_LEFT:
                targetX = 0;
                targetY = screenHeight - viewHeight;
                break;
            case BOTTOM_CENTER:
                targetX = (screenWidth - viewWidth) / 2;
                targetY = screenHeight - viewHeight;
                break;
            case BOTTOM_RIGHT:
                targetX = screenWidth - viewWidth;
                targetY = screenHeight - viewHeight;
                break;
            default:
                targetX = (screenWidth - viewWidth) / 2;
                targetY = 0;
                break;
        }

        // 使用动画平滑移动到目标位置
        view.animate()
            .translationX(targetX - layoutParams.leftMargin)
            .translationY(targetY - layoutParams.topMargin)
            .setDuration(200)
            .withEndAction(() -> {
                // 动画结束后更新实际的布局参数
                layoutParams.leftMargin = targetX;
                layoutParams.topMargin = targetY;
                view.setTranslationX(0);
                view.setTranslationY(0);
                view.setLayoutParams(layoutParams);

                // 保存位置到SharedPreferences
                savePerformanceOverlayPosition(targetX, targetY);

                // 重新配置文字对齐
                configureTextAlignment();
            })
            .start();
    }

    private void savePerformanceOverlayPosition(int x, int y) {
        SharedPreferences prefs = activity.getSharedPreferences("performance_overlay", Activity.MODE_PRIVATE);
        prefs.edit()
            .putBoolean("has_custom_position", true)
            .putInt("left_margin", x)
            .putInt("top_margin", y)
            .apply();
    }

    /**
     * 获取View的实际尺寸，如果未测量则使用估计值
     */
    private int[] getViewDimensions(View view) {
        int width = view.getWidth();
        int height = view.getHeight();

        // 如果View尺寸为0（还未测量），使用估计值
        if (width == 0) {
            width = 300; // 估计宽度
        }
        if (height == 0) {
            height = 50; // 估计高度
        }

        return new int[]{width, height};
    }

    /**
     * 获取父容器的尺寸
     */
    private int[] getParentDimensions(View view) {
        View parent = (View) view.getParent();
        return new int[]{parent.getWidth(), parent.getHeight()};
    }
}
