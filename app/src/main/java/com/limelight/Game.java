package com.limelight;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.advance_setting.ControllerManager;
import com.limelight.binding.input.capture.InputCaptureManager;
import com.limelight.binding.input.capture.InputCaptureProvider;
import com.limelight.binding.input.touch.AbsoluteTouchContext;
import com.limelight.binding.input.touch.NativeTouchContext;
import com.limelight.binding.input.touch.RelativeTouchContext;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.binding.input.evdev.EvdevListener;
import com.limelight.binding.input.touch.TouchContext;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.binding.video.CrashListener;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.PerfOverlayListener;
import com.limelight.binding.video.PerformanceInfo;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PerfOverlayDisplayItemsPreference;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.GameGestures;
import com.limelight.ui.StreamView;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;
import com.limelight.utils.NetHelper;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.View.OnGenericMotionListener;
import android.view.View.OnSystemUiVisibilityChangeListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.widget.FrameLayout;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Gravity;
import android.util.DisplayMetrics;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.app.Presentation;

import org.json.JSONException;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

public class Game extends Activity implements SurfaceHolder.Callback,
        OnGenericMotionListener, OnTouchListener, NvConnectionListener, EvdevListener,
        OnSystemUiVisibilityChangeListener, GameGestures, StreamView.InputCallbacks,
        PerfOverlayListener, UsbDriverService.UsbDriverStateListener, View.OnKeyListener {
    private int lastButtonState = 0;
    private static final int TOUCH_CONTEXT_LENGTH = 2;

    private long lastTotalRxBytes = TrafficStats.getTotalRxBytes();
    private long lastTimestampMS = System.currentTimeMillis();

    // Only 2 touches are supported
    private TouchContext[] touchContextMap = new TouchContext[TOUCH_CONTEXT_LENGTH];
    private final TouchContext[] absoluteTouchContextMap = new TouchContext[TOUCH_CONTEXT_LENGTH];
    private final TouchContext[] relativeTouchContextMap = new TouchContext[TOUCH_CONTEXT_LENGTH];
    private long multiFingerDownTime = 0;

    public static final int REFERENCE_HORIZ_RES = 1280;
    public static final int REFERENCE_VERT_RES = 720;

    private static final int STYLUS_DOWN_DEAD_ZONE_DELAY = 100;
    private static final int STYLUS_DOWN_DEAD_ZONE_RADIUS = 20;

    private static final int STYLUS_UP_DEAD_ZONE_DELAY = 150;
    private static final int STYLUS_UP_DEAD_ZONE_RADIUS = 50;

    private static final int MULTI_FINGER_TAP_THRESHOLD = 300;

    private ControllerHandler controllerHandler;
    private KeyboardTranslator keyboardTranslator;
    private VirtualController virtualController;

    public interface PerformanceInfoDisplay{
        void display(Map<String,String> performanceAttrs);
    }
    private ControllerManager controllerManager;
    private List<PerformanceInfoDisplay> performanceInfoDisplays = new ArrayList<>();


    PreferenceConfiguration prefConfig;
    private SharedPreferences tombstonePrefs;

    private NvConnection conn;
    private SpinnerDialog spinner;
    private boolean displayedFailureDialog = false;
    private boolean connecting = false;
    private boolean connected = false;
    private boolean autoEnterPip = false;
    private boolean surfaceCreated = false;
    private boolean attemptedConnection = false;
    private int suppressPipRefCount = 0;
    private String pcName;
    private String appName;
    private NvApp app;
    private float desiredRefreshRate;

    private InputCaptureProvider inputCaptureProvider;
    private int modifierFlags = 0;
    private boolean grabbedInput = true;
    private boolean cursorVisible = false;
    private boolean waitingForAllModifiersUp = false;
    private int specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private StreamView streamView;
    private long lastAbsTouchUpTime = 0;
    private long lastAbsTouchDownTime = 0;
    private float lastAbsTouchUpX, lastAbsTouchUpY;
    private float lastAbsTouchDownX, lastAbsTouchDownY;
    private long previousTimeMillis = 0;
    private long previousRxBytes = 0;

    // ESC键双击相关变量
    private static final long ESC_DOUBLE_PRESS_INTERVAL = 500; // 500毫秒内按第二次ESC才有效
    private long lastEscPressTime = 0;
    private boolean hasShownEscHint = false;

    private boolean isHidingOverlays;
    private TextView notificationOverlayView;
    private int requestedNotificationOverlayVisibility = View.GONE;
    private LinearLayout performanceOverlayView;
    private int requestedPerformanceOverlayVisibility = View.GONE;

    // 性能覆盖层拖动相关
    private boolean isDraggingPerfOverlay = false;
    private float perfOverlayStartX, perfOverlayStartY;
    private float perfOverlayDeltaX, perfOverlayDeltaY;
    private static final int SNAP_THRESHOLD = 100; // 吸附阈值（像素）
    
    // 8个吸附位置的枚举
    private enum SnapPosition {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    private MediaCodecDecoderRenderer decoderRenderer;
    private boolean reportedCrash;

    private WifiManager.WifiLock highPerfWifiLock;
    private WifiManager.WifiLock lowLatencyWifiLock;
    private Map<Integer, NativeTouchContext.Pointer> nativeTouchPointerMap = new HashMap<>();

    private boolean connectedToUsbDriverService = false;
    private ServiceConnection usbDriverServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            UsbDriverService.UsbDriverBinder binder = (UsbDriverService.UsbDriverBinder) iBinder;
            binder.setListener(controllerHandler);
            binder.setStateListener(Game.this);
            binder.start();
            connectedToUsbDriverService = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            connectedToUsbDriverService = false;
        }
    };

    private TextView perfResView;
    private TextView perfDecoderView;
    private TextView perfRenderFpsView;
    private TextView networkLatencyView;
    private TextView decodeLatencyView;
    private TextView hostLatencyView;
    private TextView packetLossView;

    public static final String EXTRA_HOST = "Host";
    public static final String EXTRA_PORT = "Port";
    public static final String EXTRA_HTTPS_PORT = "HttpsPort";
    public static final String EXTRA_APP_NAME = "AppName";
    public static final String EXTRA_APP_ID = "AppId";
    public static final String EXTRA_UNIQUEID = "UniqueId";
    public static final String EXTRA_PC_UUID = "UUID";
    public static final String EXTRA_PC_NAME = "PcName";
    public static final String EXTRA_PAIR_NAME = "PairName";
    public static final String EXTRA_APP_HDR = "HDR";
    public static final String EXTRA_SERVER_CERT = "ServerCert";
    public static final String EXTRA_PC_USEVDD = "usevdd";
    public static final String EXTRA_APP_CMD = "CmdList";

    private DisplayManager displayManager;
    private Display externalDisplay;
    private boolean useExternalDisplay = false;
    private DisplayManager.DisplayListener displayListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiHelper.setLocale(this);

        // We don't want a title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Full-screen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // If we're going to use immersive mode, we want to have
        // the entire screen
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);

        // Listen for UI visibility events
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(this);

        // Change volume button behavior
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Inflate the content
        setContentView(R.layout.activity_game);

        // Hack: allows use keyboard by dpad or controller
        getWindow().getDecorView().findViewById(android.R.id.content).setFocusable(true);

        // Start the spinner
        spinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.conn_establishing_title),
                getResources().getString(R.string.conn_establishing_msg), true);

        // Read the stream preferences
        prefConfig = PreferenceConfiguration.readPreferences(this);
        tombstonePrefs = Game.this.getSharedPreferences("DecoderTombstone", 0);
        // Set flat region size for long press jitter elimination.
        NativeTouchContext.INTIAL_ZONE_PIXELS = prefConfig.longPressflatRegionPixels;
        NativeTouchContext.ENABLE_ENHANCED_TOUCH = prefConfig.enableEnhancedTouch;
        if(prefConfig.enhancedTouchOnWhichSide){
            NativeTouchContext.ENHANCED_TOUCH_ON_RIGHT = -1;
        }else{
            NativeTouchContext.ENHANCED_TOUCH_ON_RIGHT = 1;
        }
        NativeTouchContext.ENHANCED_TOUCH_ZONE_DIVIDER = prefConfig.enhanceTouchZoneDivider * 0.01f;
        NativeTouchContext.POINTER_VELOCITY_FACTOR = prefConfig.pointerVelocityFactor * 0.01f;
        // NativeTouchContext.POINTER_FIXED_X_VELOCITY = prefConfig.pointerFixedXVelocity;

        // Enter landscape unless we're on a square screen
        setPreferredOrientationForCurrentDisplay();

        if (prefConfig.stretchVideo || shouldIgnoreInsetsForResolution(prefConfig.width, prefConfig.height)) {
            // Allow the activity to layout under notches if the fill-screen option
            // was turned on by the user or it's a full-screen native resolution
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }

        // Listen for non-touch events on the game surface
        streamView = findViewById(R.id.surfaceView);
        streamView.setOnGenericMotionListener(this);
        streamView.setOnKeyListener(this);
        streamView.setInputCallbacks(this);

        // Listen for touch events on the background touch view to enable trackpad mode
        // to work on areas outside of the StreamView itself. We use a separate View
        // for this rather than just handling it at the Activity level, because that
        // allows proper touch splitting, which the OSC relies upon.
        View backgroundTouchView = findViewById(R.id.backgroundTouchView);
        backgroundTouchView.setOnTouchListener(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Request unbuffered input event dispatching for all input classes we handle here.
            // Without this, input events are buffered to be delivered in lock-step with VBlank,
            // artificially increasing input latency while streaming.
            streamView.requestUnbufferedDispatch(
                    InputDevice.SOURCE_CLASS_BUTTON | // Keyboards
                    InputDevice.SOURCE_CLASS_JOYSTICK | // Gamepads
                    InputDevice.SOURCE_CLASS_POINTER | // Touchscreens and mice (w/o pointer capture)
                    InputDevice.SOURCE_CLASS_POSITION | // Touchpads
                    InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            );
            backgroundTouchView.requestUnbufferedDispatch(
                    InputDevice.SOURCE_CLASS_BUTTON | // Keyboards
                    InputDevice.SOURCE_CLASS_JOYSTICK | // Gamepads
                    InputDevice.SOURCE_CLASS_POINTER | // Touchscreens and mice (w/o pointer capture)
                    InputDevice.SOURCE_CLASS_POSITION | // Touchpads
                    InputDevice.SOURCE_CLASS_TRACKBALL // Mice (pointer capture)
            );
        }

        notificationOverlayView = findViewById(R.id.notificationOverlay);

        performanceOverlayView = findViewById(R.id.performanceOverlay);

        perfResView = findViewById(R.id.perfRes);
        perfDecoderView = findViewById(R.id.perfDecoder);
        perfRenderFpsView = findViewById(R.id.perfRenderFps);
        networkLatencyView = findViewById(R.id.perfNetworkLatency);
        decodeLatencyView = findViewById(R.id.perfDecodeLatency);
        hostLatencyView = findViewById(R.id.perfHostLatency);
        packetLossView = findViewById(R.id.perfPacketLoss);

        inputCaptureProvider = InputCaptureManager.getInputCaptureProvider(this, this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            streamView.setOnCapturedPointerListener(new View.OnCapturedPointerListener() {
                @Override
                public boolean onCapturedPointer(View view, MotionEvent motionEvent) {
                    return handleMotionEvent(view, motionEvent);
                }
            });
        }

        // Warn the user if they're on a metered connection
        ConnectivityManager connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connMgr.isActiveNetworkMetered()) {
            displayTransientMessage(getResources().getString(R.string.conn_metered));
        }

        // Make sure Wi-Fi is fully powered up
        WifiManager wifiMgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            highPerfWifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Moonlight High Perf Lock");
            highPerfWifiLock.setReferenceCounted(false);
            highPerfWifiLock.acquire();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lowLatencyWifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Moonlight Low Latency Lock");
                lowLatencyWifiLock.setReferenceCounted(false);
                lowLatencyWifiLock.acquire();
            }
        } catch (SecurityException e) {
            // Some Samsung Galaxy S10+/S10e devices throw a SecurityException from
            // WifiLock.acquire() even though we have android.permission.WAKE_LOCK in our manifest.
            e.printStackTrace();
        }

        appName = Game.this.getIntent().getStringExtra(EXTRA_APP_NAME);
        pcName = Game.this.getIntent().getStringExtra(EXTRA_PC_NAME);

        String host = Game.this.getIntent().getStringExtra(EXTRA_HOST);
        int port = Game.this.getIntent().getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT);
        int httpsPort = Game.this.getIntent().getIntExtra(EXTRA_HTTPS_PORT, 0); // 0 is treated as unknown
        int appId = Game.this.getIntent().getIntExtra(EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
        String uniqueId = Game.this.getIntent().getStringExtra(EXTRA_UNIQUEID);
        String pairName = Game.this.getIntent().getStringExtra(EXTRA_PAIR_NAME);
        boolean appSupportsHdr = Game.this.getIntent().getBooleanExtra(EXTRA_APP_HDR, false);
        boolean pcUseVdd = Game.this.getIntent().getBooleanExtra(EXTRA_PC_USEVDD, false);
        byte[] derCertData = Game.this.getIntent().getByteArrayExtra(EXTRA_SERVER_CERT);
        String cmdList = Game.this.getIntent().getStringExtra(EXTRA_APP_CMD);

        app = new NvApp(appName != null ? appName : "app", appId, appSupportsHdr);
        if (cmdList != null) {
            app.setCmdList(cmdList);
        }
        X509Certificate serverCert = null;
        try {
            if (derCertData != null) {
                serverCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(derCertData));
            }
        } catch (CertificateException e) {
            e.printStackTrace();
        }

        if (appId == StreamConfiguration.INVALID_APP_ID) {
            finish();
            return;
        }

        // Initialize the MediaCodec helper before creating the decoder
        GlPreferences glPrefs = GlPreferences.readPreferences(this);
        MediaCodecHelper.initialize(this, glPrefs.glRenderer);

        // Check if the user has enabled HDR
        boolean willStreamHdr = false;
        if (prefConfig.enableHdr) {
            // Start our HDR checklist
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Display display = getTargetDisplay();
                Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();

                // We must now ensure our display is compatible with HDR10
                if (hdrCaps != null) {
                    // getHdrCapabilities() returns null on Lenovo Lenovo Mirage Solo (vega), Android 8.0
                    for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                        if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                            willStreamHdr = true;
                            break;
                        }
                    }
                }

                if (!willStreamHdr) {
                    // Nope, no HDR for us :(
                    Toast.makeText(this, "Display does not support HDR10", Toast.LENGTH_LONG).show();
                }
            }
            else {
                Toast.makeText(this, "HDR requires Android 7.0 or later", Toast.LENGTH_LONG).show();
            }
        }

        // Check if the user has enabled performance stats overlay
        if (prefConfig.enablePerfOverlay) {
            requestedPerformanceOverlayVisibility = View.VISIBLE;
            performanceOverlayView.setVisibility(View.VISIBLE);
            // 配置性能覆盖层的方向和位置
            configurePerformanceOverlay();
        }

        decoderRenderer = new MediaCodecDecoderRenderer(
                this,
                prefConfig,
                new CrashListener() {
                    @Override
                    public void notifyCrash(Exception e) {
                        // The MediaCodec instance is going down due to a crash
                        // let's tell the user something when they open the app again

                        // We must use commit because the app will crash when we return from this function
                        tombstonePrefs.edit().putInt("CrashCount", tombstonePrefs.getInt("CrashCount", 0) + 1).commit();
                        reportedCrash = true;
                    }
                },
                tombstonePrefs.getInt("CrashCount", 0),
                connMgr.isActiveNetworkMetered(),
                willStreamHdr,
                glPrefs.glRenderer,
                this);

        // Don't stream HDR if the decoder can't support it
        if (willStreamHdr && !decoderRenderer.isHevcMain10Hdr10Supported() && !decoderRenderer.isAv1Main10Supported()) {
            willStreamHdr = false;
            Toast.makeText(this, "Decoder does not support HDR10 profile", Toast.LENGTH_LONG).show();
        }

        // Display a message to the user if HEVC was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC && !decoderRenderer.isHevcSupported()) {
            Toast.makeText(this, "No HEVC decoder found", Toast.LENGTH_LONG).show();
        }

        // Display a message to the user if AV1 was forced on but we still didn't find a decoder
        if (prefConfig.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 && !decoderRenderer.isAv1Supported()) {
            Toast.makeText(this, "No AV1 decoder found", Toast.LENGTH_LONG).show();
        }

        // H.264 is always supported
        int supportedVideoFormats = MoonBridge.VIDEO_FORMAT_H264;
        if (decoderRenderer.isHevcSupported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265;
            if (willStreamHdr && decoderRenderer.isHevcMain10Hdr10Supported()) {
                supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_H265_MAIN10;
            }
        }
        if (decoderRenderer.isAv1Supported()) {
            supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN8;
            if (willStreamHdr && decoderRenderer.isAv1Main10Supported()) {
                supportedVideoFormats |= MoonBridge.VIDEO_FORMAT_AV1_MAIN10;
            }
        }

        int gamepadMask = ControllerHandler.getAttachedControllerMask(this);
        if (!prefConfig.multiController) {
            // Always set gamepad 1 present for when multi-controller is
            // disabled for games that don't properly support detection
            // of gamepads removed and replugged at runtime.
            gamepadMask = 1;
        }
        if (prefConfig.onscreenController) {
            // If we're using OSC, always set at least gamepad 1.
            gamepadMask |= 1;
        }

        // Set to the optimal mode for streaming
        float displayRefreshRate = prepareDisplayForRendering();
        LimeLog.info("Display refresh rate: "+displayRefreshRate);

        // If the user requested frame pacing using a capped FPS, we will need to change our
        // desired FPS setting here in accordance with the active display refresh rate.
        int roundedRefreshRate = Math.round(displayRefreshRate);
        int chosenFrameRate = prefConfig.fps; //将此处chosenFrameRate赋值为5时， 视频刷新率降低到5，但直接观察远端桌面可知，触控刷新率并未下降，窗口仍可流畅拖动。
        if (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            if (prefConfig.fps >= roundedRefreshRate) {
                if (prefConfig.fps > roundedRefreshRate + 3) {
                    // Use frame drops when rendering above the screen frame rate
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    LimeLog.info("Using drop mode for FPS > Hz");
                } else if (roundedRefreshRate <= 49) {
                    // Let's avoid clearly bogus refresh rates and fall back to legacy rendering
                    prefConfig.framePacing = PreferenceConfiguration.FRAME_PACING_BALANCED;
                    LimeLog.info("Bogus refresh rate: " + roundedRefreshRate);
                }
                else {
                    chosenFrameRate = roundedRefreshRate - 1;
                    LimeLog.info("Adjusting FPS target for screen to " + chosenFrameRate);
                }
            }
        }

        StreamConfiguration config = new StreamConfiguration.Builder()
                .setResolution(prefConfig.width, prefConfig.height)
                .setLaunchRefreshRate(prefConfig.fps)
                .setRefreshRate(chosenFrameRate)  //将此处chosenFrameRate替换为5时， 视频刷新率降低到5，但直接观察远端桌面可知，触控刷新率并未下降，窗口仍可流畅拖动。
                .setApp(app)
                .setBitrate(prefConfig.bitrate)
                .setResolutionScale(prefConfig.resolutionScale)
                .setEnableSops(prefConfig.enableSops)
                .enableLocalAudioPlayback(prefConfig.playHostAudio)
                .setMaxPacketSize(1392)
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO) // NvConnection will perform LAN and VPN detection
                .setSupportedVideoFormats(supportedVideoFormats)
                .setAttachedGamepadMask(gamepadMask)
                .setClientRefreshRateX100((int)(displayRefreshRate * 100))
                .setAudioConfiguration(prefConfig.audioConfiguration)
                .setColorSpace(decoderRenderer.getPreferredColorSpace())
                .setColorRange(decoderRenderer.getPreferredColorRange())
                .setPersistGamepadsAfterDisconnect(!prefConfig.multiController)
                .setUseVdd(pcUseVdd)
                .build();

        // Initialize the connection
        conn = new NvConnection(getApplicationContext(),
                new ComputerDetails.AddressTuple(host, port),
                httpsPort, uniqueId, pairName, config,
                PlatformBinding.getCryptoProvider(this), serverCert);
        controllerHandler = new ControllerHandler(this, conn, this, prefConfig);
        keyboardTranslator = new KeyboardTranslator();

        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        inputManager.registerInputDeviceListener(keyboardTranslator, null);


        // Initialize touch contexts
        for (int i = 0; i < TOUCH_CONTEXT_LENGTH; i++) {
            absoluteTouchContextMap[i] = new AbsoluteTouchContext(conn, i, streamView);
            relativeTouchContextMap[i] = new RelativeTouchContext(conn, i,
                    streamView, prefConfig);
        }
        if (!prefConfig.touchscreenTrackpad) {
            touchContextMap = absoluteTouchContextMap;
        }
        else {
            touchContextMap = relativeTouchContextMap;
        }

        if (prefConfig.onscreenController) {
            // create virtual onscreen controller
            virtualController = new VirtualController(controllerHandler,
                    (FrameLayout)streamView.getParent(),
                    this);
            virtualController.refreshLayout();
            virtualController.show();
        }

        if (prefConfig.onscreenKeyboard) {
            // create virtual onscreen keyboard
            controllerManager = new ControllerManager((FrameLayout)streamView.getParent(),this);
            controllerManager.refreshLayout();
        }

        if (prefConfig.usbDriver) {
            // Start the USB driver
            bindService(new Intent(this, UsbDriverService.class),
                    usbDriverServiceConnection, Service.BIND_AUTO_CREATE);
        }

        if (!decoderRenderer.isAvcSupported()) {
            if (spinner != null) {
                spinner.dismiss();
                spinner = null;
            }

            // If we can't find an AVC decoder, we can't proceed
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title),
                    "This device or ROM doesn't support hardware accelerated H.264 playback.", true);
            return;
        }

        // The connection will be started when the surface gets created
        streamView.getHolder().addCallback(this);

        // 允许内容延伸到刘海区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = 
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        // Set up display position
        setupDisplayPosition();

        // 初始化显示管理器
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);

        // 设置显示器监听器
        setupDisplayListener();

        // 检查是否有外接显示器
        checkForExternalDisplay();

        // 如果有外接显示器，启动外接显示器演示，并降低内建屏幕亮度到30%
        if (useExternalDisplay) {
            Window window = getWindow();
            if (window != null) {
                WindowManager.LayoutParams layoutParams = window.getAttributes();
                layoutParams.screenBrightness = 0.3f;
                window.setAttributes(layoutParams);
            }
            startExternalDisplayPresentation();
        }
    }

    private void setPreferredOrientationForCurrentDisplay() {
        Display display = getTargetDisplay();

        // 首先确定基于分辨率的所需方向
        int desiredOrientation = Configuration.ORIENTATION_UNDEFINED;
        
        // 根据配置的宽高比确定横屏或竖屏
        if (prefConfig.width > prefConfig.height) {
            desiredOrientation = Configuration.ORIENTATION_LANDSCAPE;
        } else if (prefConfig.height > prefConfig.width) {
            desiredOrientation = Configuration.ORIENTATION_PORTRAIT;
        } else {
            // 宽高相等的情况
            // 如果使用屏幕控制器，默认使用横屏
            if (prefConfig.onscreenController || prefConfig.onscreenKeyboard) {
                desiredOrientation = Configuration.ORIENTATION_LANDSCAPE;
            }
        }
        
        // 对于接近正方形的屏幕，应用更复杂的逻辑
        if (PreferenceConfiguration.isSquarishScreen(display)) {
            if (desiredOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
            } else if (desiredOrientation == Configuration.ORIENTATION_PORTRAIT) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            } else {
                // 没有明确的理由锁定为横屏或竖屏时，允许任意方向
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
            }
        } else {
            // 对于非方形屏幕，按照分辨率决定方向
            if (desiredOrientation == Configuration.ORIENTATION_PORTRAIT) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            } else {
                // 默认或横屏情况
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Set requested orientation for possible new screen size
        setPreferredOrientationForCurrentDisplay();

        if (virtualController != null) {
            // Refresh layout of OSC for possible new screen size
            virtualController.refreshLayout();
        }
        if (controllerManager != null) {
            // Refresh layout of OSC for possible new screen size
            controllerManager.refreshLayout();
        }

        // Hide on-screen overlays in PiP mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isInPictureInPictureMode()) {
                isHidingOverlays = true;

                if (virtualController != null) {
                    virtualController.hide();
                }

                performanceOverlayView.setVisibility(View.GONE);
                notificationOverlayView.setVisibility(View.GONE);

                // Disable sensors while in PiP mode
                controllerHandler.disableSensors();

                // Update GameManager state to indicate we're in PiP (still gaming, but interruptible)
                UiHelper.notifyStreamEnteringPiP(this);
            }
            else {
                isHidingOverlays = false;

                // Restore overlays to previous state when leaving PiP

                if (virtualController != null) {
                    virtualController.show();
                }

                performanceOverlayView.setVisibility(requestedPerformanceOverlayVisibility);
                notificationOverlayView.setVisibility(requestedNotificationOverlayVisibility);

                // Enable sensors again after exiting PiP
                controllerHandler.enableSensors();

                // Update GameManager state to indicate we're out of PiP (gaming, non-interruptible)
                UiHelper.notifyStreamExitingPiP(this);
            }
        }

        // Re-apply display position
        setupDisplayPosition();
    }

    @TargetApi(Build.VERSION_CODES.O)
    private PictureInPictureParams getPictureInPictureParams(boolean autoEnter) {
        PictureInPictureParams.Builder builder =
                new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(prefConfig.width, prefConfig.height))
                        .setSourceRectHint(new Rect(
                                streamView.getLeft(), streamView.getTop(),
                                streamView.getRight(), streamView.getBottom()));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter);
            builder.setSeamlessResizeEnabled(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (appName != null) {
                builder.setTitle(appName);
                if (pcName != null) {
                    builder.setSubtitle(pcName);
                }
            }
            else if (pcName != null) {
                builder.setTitle(pcName);
            }
        }

        return builder.build();
    }

    private void updatePipAutoEnter() {
        if (!prefConfig.enablePip) {
            return;
        }

        boolean autoEnter = connected && suppressPipRefCount == 0;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(getPictureInPictureParams(autoEnter));
        }
        else {
            autoEnterPip = autoEnter;
        }
    }

    public void setMetaKeyCaptureState(boolean enabled) {
        // This uses custom APIs present on some Samsung devices to allow capture of
        // meta key events while streaming.
        try {
            Class<?> semWindowManager = Class.forName("com.samsung.android.view.SemWindowManager");
            Method getInstanceMethod = semWindowManager.getMethod("getInstance");
            Object manager = getInstanceMethod.invoke(null);

            if (manager != null) {
                Class<?>[] parameterTypes = new Class<?>[2];
                parameterTypes[0] = ComponentName.class;
                parameterTypes[1] = boolean.class;
                Method requestMetaKeyEventMethod = semWindowManager.getDeclaredMethod("requestMetaKeyEvent", parameterTypes);
                requestMetaKeyEventMethod.invoke(manager, this.getComponentName(), enabled);
            }
            else {
                LimeLog.warning("SemWindowManager.getInstance() returned null");
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();

        // PiP is only supported on Oreo and later, and we don't need to manually enter PiP on
        // Android S and later. On Android R, we will use onPictureInPictureRequested() instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (autoEnterPip) {
                try {
                    // This has thrown all sorts of weird exceptions on Samsung devices
                    // running Oreo. Just eat them and close gracefully on leave, rather
                    // than crashing.
                    enterPictureInPictureMode(getPictureInPictureParams(false));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.R)
    public boolean onPictureInPictureRequested() {
        // Enter PiP when requested unless we're on Android 12 which supports auto-enter.
        if (autoEnterPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPictureInPictureMode(getPictureInPictureParams(false));
        }
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        // We can't guarantee the state of modifiers keys which may have
        // lifted while focus was not on us. Clear the modifier state.
        this.modifierFlags = 0;

        // With Android native pointer capture, capture is lost when focus is lost,
        // so it must be requested again when focus is regained.
        inputCaptureProvider.onWindowFocusChanged(hasFocus);
    }

    private boolean isRefreshRateEqualMatch(float refreshRate) {
        return refreshRate >= prefConfig.fps &&
                refreshRate <= prefConfig.fps + 3;
    }

    private boolean isRefreshRateGoodMatch(float refreshRate) {
        return refreshRate >= prefConfig.fps &&
                Math.round(refreshRate) % prefConfig.fps <= 3;
    }

    private boolean shouldIgnoreInsetsForResolution(int width, int height) {
        // Never ignore insets for non-native resolutions
        if (!PreferenceConfiguration.isNativeResolution(width, height)) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display display = getWindowManager().getDefaultDisplay();
            for (Display.Mode candidate : display.getSupportedModes()) {
                // Ignore insets if this is an exact match for the display resolution
                if ((width == candidate.getPhysicalWidth() && height == candidate.getPhysicalHeight()) ||
                        (height == candidate.getPhysicalWidth() && width == candidate.getPhysicalHeight())) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean mayReduceRefreshRate() {
        return prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS ||
                prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                (prefConfig.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED && prefConfig.reduceRefreshRate);
    }

    private float prepareDisplayForRendering() {
        Display display = getTargetDisplay();
        WindowManager.LayoutParams windowLayoutParams = getWindow().getAttributes();
        float displayRefreshRate;

        // On M, we can explicitly set the optimal display mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode bestMode = display.getMode();
            boolean isNativeResolutionStream = PreferenceConfiguration.isNativeResolution(prefConfig.width, prefConfig.height);
            boolean refreshRateIsGood = isRefreshRateGoodMatch(bestMode.getRefreshRate());
            boolean refreshRateIsEqual = isRefreshRateEqualMatch(bestMode.getRefreshRate());

            LimeLog.info("Current display mode: "+bestMode.getPhysicalWidth()+"x"+
                    bestMode.getPhysicalHeight()+"x"+bestMode.getRefreshRate());

            for (Display.Mode candidate : display.getSupportedModes()) {
                boolean refreshRateReduced = candidate.getRefreshRate() < bestMode.getRefreshRate();
                boolean resolutionReduced = candidate.getPhysicalWidth() < bestMode.getPhysicalWidth() ||
                        candidate.getPhysicalHeight() < bestMode.getPhysicalHeight();
                boolean resolutionFitsStream = candidate.getPhysicalWidth() >= prefConfig.width &&
                        candidate.getPhysicalHeight() >= prefConfig.height;

                LimeLog.info("Examining display mode: "+candidate.getPhysicalWidth()+"x"+
                        candidate.getPhysicalHeight()+"x"+candidate.getRefreshRate());

                if (candidate.getPhysicalWidth() > 4096 && prefConfig.width <= 4096) {
                    // Avoid resolutions options above 4K to be safe
                    continue;
                }

                // On non-4K streams, we force the resolution to never change unless it's above
                // 60 FPS, which may require a resolution reduction due to HDMI bandwidth limitations,
                // or it's a native resolution stream.
                if (prefConfig.width < 3840 && prefConfig.fps <= 60 && !isNativeResolutionStream) {
                    if (display.getMode().getPhysicalWidth() != candidate.getPhysicalWidth() ||
                            display.getMode().getPhysicalHeight() != candidate.getPhysicalHeight()) {
                        continue;
                    }
                }

                // Make sure the resolution doesn't regress unless if it's over 60 FPS
                // where we may need to reduce resolution to achieve the desired refresh rate.
                if (resolutionReduced && !(prefConfig.fps > 60 && resolutionFitsStream)) {
                    continue;
                }

                if (mayReduceRefreshRate() && refreshRateIsEqual && !isRefreshRateEqualMatch(candidate.getRefreshRate())) {
                    // If we had an equal refresh rate and this one is not, skip it. In min latency
                    // mode, we want to always prefer the highest frame rate even though it may cause
                    // microstuttering.
                    continue;
                }
                else if (refreshRateIsGood) {
                    // We've already got a good match, so if this one isn't also good, it's not
                    // worth considering at all.
                    if (!isRefreshRateGoodMatch(candidate.getRefreshRate())) {
                        continue;
                    }

                    if (mayReduceRefreshRate()) {
                        // User asked for the lowest possible refresh rate, so don't raise it if we
                        // have a good match already
                        if (candidate.getRefreshRate() > bestMode.getRefreshRate()) {
                            continue;
                        }
                    }
                    else {
                        // User asked for the highest possible refresh rate, so don't reduce it if we
                        // have a good match already
                        if (refreshRateReduced) {
                            continue;
                        }
                    }
                }
                else if (!isRefreshRateGoodMatch(candidate.getRefreshRate())) {
                    // We didn't have a good match and this match isn't good either, so just don't
                    // reduce the refresh rate.
                    if (refreshRateReduced) {
                        continue;
                    }
                } else {
                    // We didn't have a good match and this match is good. Prefer this refresh rate
                    // even if it reduces the refresh rate. Lowering the refresh rate can be beneficial
                    // when streaming a 60 FPS stream on a 90 Hz device. We want to select 60 Hz to
                    // match the frame rate even if the active display mode is 90 Hz.
                }

                bestMode = candidate;
                refreshRateIsGood = isRefreshRateGoodMatch(candidate.getRefreshRate());
                refreshRateIsEqual = isRefreshRateEqualMatch(candidate.getRefreshRate());
            }

            LimeLog.info("Best display mode: "+bestMode.getPhysicalWidth()+"x"+
                    bestMode.getPhysicalHeight()+"x"+bestMode.getRefreshRate());

            // Only apply new window layout parameters if we've actually changed the display mode
            if (display.getMode().getModeId() != bestMode.getModeId()) {
                // If we only changed refresh rate and we're on an OS that supports Surface.setFrameRate()
                // use that instead of using preferredDisplayModeId to avoid the possibility of triggering
                // bugs that can cause the system to switch from 4K60 to 4K24 on Chromecast 4K.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || UiHelper.isColorOS() ||
                        display.getMode().getPhysicalWidth() != bestMode.getPhysicalWidth() ||
                        display.getMode().getPhysicalHeight() != bestMode.getPhysicalHeight()) {
                    // Apply the display mode change
                    windowLayoutParams.preferredDisplayModeId = bestMode.getModeId();
                    getWindow().setAttributes(windowLayoutParams);
                }
                else {
                    LimeLog.info("Using setFrameRate() instead of preferredDisplayModeId due to matching resolution");
                }
            }
            else {
                LimeLog.info("Current display mode is already the best display mode");
            }

            displayRefreshRate = bestMode.getRefreshRate();
        }
        // On L, we can at least tell the OS that we want a refresh rate
        else {
            float bestRefreshRate = display.getRefreshRate();
            for (float candidate : display.getSupportedRefreshRates()) {
                LimeLog.info("Examining refresh rate: "+candidate);

                if (candidate > bestRefreshRate) {
                    // Ensure the frame rate stays around 60 Hz for <= 60 FPS streams
                    if (prefConfig.fps <= 60) {
                        if (candidate >= 63) {
                            continue;
                        }
                    }

                    bestRefreshRate = candidate;
                }
            }

            LimeLog.info("Selected refresh rate: "+bestRefreshRate);
            windowLayoutParams.preferredRefreshRate = bestRefreshRate;
            displayRefreshRate = bestRefreshRate;

            // Apply the refresh rate change
            getWindow().setAttributes(windowLayoutParams);
        }

        // Until Marshmallow, we can't ask for a 4K display mode, so we'll
        // need to hint the OS to provide one.
        boolean aspectRatioMatch = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // We'll calculate whether we need to scale by aspect ratio. If not, we'll use
            // setFixedSize so we can handle 4K properly. The only known devices that have
            // >= 4K screens have exactly 4K screens, so we'll be able to hit this good path
            // on these devices. On Marshmallow, we can start changing to 4K manually but no
            // 4K devices run 6.0 at the moment.
            Point screenSize = new Point(0, 0);
            display.getSize(screenSize);

            double screenAspectRatio = ((double)screenSize.y) / screenSize.x;
            double streamAspectRatio = ((double)prefConfig.height) / prefConfig.width;
            if (Math.abs(screenAspectRatio - streamAspectRatio) < 0.001) {
                LimeLog.info("Stream has compatible aspect ratio with output display");
                aspectRatioMatch = true;
            }
        }

        if (prefConfig.stretchVideo || aspectRatioMatch) {
            // Set the surface to the size of the video
            streamView.getHolder().setFixedSize(prefConfig.width, prefConfig.height);
        }
        else {
            // Set the surface to scale based on the aspect ratio of the stream
            streamView.setDesiredAspectRatio((double)prefConfig.width / (double)prefConfig.height);
        }

        // Set the desired refresh rate that will get passed into setFrameRate() later
        desiredRefreshRate = displayRefreshRate;

        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // TVs may take a few moments to switch refresh rates, and we can probably assume
            // it will be eventually activated.
            // TODO: Improve this
            return displayRefreshRate;
        }
        else {
            // Use the lower of the current refresh rate and the selected refresh rate.
            // The preferred refresh rate may not actually be applied (ex: Battery Saver mode).
            return Math.min(getWindowManager().getDefaultDisplay().getRefreshRate(), displayRefreshRate);
        }
    }

    @SuppressLint("InlinedApi")
    private final Runnable hideSystemUi = new Runnable() {
        @Override
        public void run() {
            // TODO: Do we want to use WindowInsetsController here on R+ instead of
            // SYSTEM_UI_FLAG_IMMERSIVE_STICKY? They seem to do the same thing as of S...

            // In multi-window mode on N+, we need to drop our layout flags or we'll
            // be drawing underneath the system UI.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode()) {
                Game.this.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
            else {
                // Use immersive mode
                Game.this.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        }
    };

    private void hideSystemUi(int delay) {
        Handler h = getWindow().getDecorView().getHandler();
        if (h != null) {
            h.removeCallbacks(hideSystemUi);
            h.postDelayed(hideSystemUi, delay);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.N)
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
        super.onMultiWindowModeChanged(isInMultiWindowMode);

        // In multi-window, we don't want to use the full-screen layout
        // flag. It will cause us to collide with the system UI.
        // This function will also be called for PiP so we can cover
        // that case here too.
        if (isInMultiWindowMode) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decoderRenderer.notifyVideoBackground();
        }
        else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decoderRenderer.notifyVideoForeground();
        }

        // Correct the system UI visibility flags
        hideSystemUi(50);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (controllerHandler != null) {
            controllerHandler.destroy();
        }
        if (keyboardTranslator != null) {
            InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
            inputManager.unregisterInputDeviceListener(keyboardTranslator);
        }

        if (lowLatencyWifiLock != null) {
            lowLatencyWifiLock.release();
        }
        if (highPerfWifiLock != null) {
            highPerfWifiLock.release();
        }

        if (connectedToUsbDriverService) {
            // Unbind from the discovery service
            unbindService(usbDriverServiceConnection);
        }

        // Destroy the capture provider
        inputCaptureProvider.destroy();

        // 清理外接显示器演示
        if (externalPresentation != null) {
            externalPresentation.dismiss();
            externalPresentation = null;
        }

        // 取消注册显示器监听器
        if (displayListener != null && displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
            displayListener = null;
        }
    }

    @Override
    protected void onPause() {
        if (isFinishing()) {
            // Stop any further input device notifications before we lose focus (and pointer capture)
            if (controllerHandler != null) {
                controllerHandler.stop();
            }

            // Ungrab input to prevent further input device notifications
            setInputGrabState(false);
        }

        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (virtualController != null) {
            virtualController.hide();
        }

        if (conn != null) {
            int videoFormat = decoderRenderer.getActiveVideoFormat();

            displayedFailureDialog = true;
            stopConnection();

            if (prefConfig.enableLatencyToast) {
                int averageEndToEndLat = decoderRenderer.getAverageEndToEndLatency();
                int averageDecoderLat = decoderRenderer.getAverageDecoderLatency();
                String message = null;
                if (averageEndToEndLat > 0) {
                    message = getResources().getString(R.string.conn_client_latency)+" "+averageEndToEndLat+" ms";
                    if (averageDecoderLat > 0) {
                        message += " ("+getResources().getString(R.string.conn_client_latency_hw)+" "+averageDecoderLat+" ms)";
                    }
                }
                else if (averageDecoderLat > 0) {
                    message = getResources().getString(R.string.conn_hardware_latency)+" "+averageDecoderLat+" ms";
                }

                // Add the video codec to the post-stream toast
                if (message != null) {
                    message += " [";

                    if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                        message += "H.264";
                    }
                    else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                        message += "HEVC";
                    }
                    else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                        message += "AV1";
                    }
                    else {
                        message += "UNKNOWN";
                    }

                    if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0) {
                        message += " HDR";
                    }

                    message += "]";
                }

                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
            }

            // Clear the tombstone count if we terminated normally
            if (!reportedCrash && tombstonePrefs.getInt("CrashCount", 0) != 0) {
                tombstonePrefs.edit()
                        .putInt("CrashCount", 0)
                        .putInt("LastNotifiedCrashCount", 0)
                        .apply();
            }
        }

        finish();
    }

    private void setInputGrabState(boolean grab) {
        // Grab/ungrab the mouse cursor
        if (grab) {
            inputCaptureProvider.enableCapture();

            // Enabling capture may hide the cursor again, so
            // we will need to show it again.
            if (cursorVisible) {
                inputCaptureProvider.showCursor();
            }
        }
        else {
            inputCaptureProvider.disableCapture();
        }

        // Grab/ungrab system keyboard shortcuts
        setMetaKeyCaptureState(grab);

        grabbedInput = grab;
    }

    private final Runnable toggleGrab = new Runnable() {
        @Override
        public void run() {
            setInputGrabState(!grabbedInput);
        }
    };

    // Returns true if the key stroke was consumed
    private boolean handleSpecialKeys(int androidKeyCode, boolean down) {
        int modifierMask = 0;
        int nonModifierKeyCode = KeyEvent.KEYCODE_UNKNOWN;

        if (androidKeyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
            androidKeyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_CTRL;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_SHIFT_LEFT ||
                 androidKeyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_SHIFT;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_ALT_LEFT ||
                 androidKeyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_ALT;
        }
        else if (androidKeyCode == KeyEvent.KEYCODE_META_LEFT ||
                androidKeyCode == KeyEvent.KEYCODE_META_RIGHT) {
            modifierMask = KeyboardPacket.MODIFIER_META;
        }
        else {
            nonModifierKeyCode = androidKeyCode;
        }

        if (down) {
            this.modifierFlags |= modifierMask;
        }
        else {
            this.modifierFlags &= ~modifierMask;
        }

        // Handle the special combos on the key up
        if (waitingForAllModifiersUp || specialKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (specialKeyCode == androidKeyCode) {
                // If this is a key up for the special key itself, eat that because the host never saw the original key down
                return true;
            }
            else if (modifierFlags != 0) {
                // While we're waiting for modifiers to come up, eat all key downs and allow all key ups to pass
                return down;
            }
            else {
                // When all modifiers are up, perform the special action
                switch (specialKeyCode) {
                    // Toggle input grab
                    case KeyEvent.KEYCODE_Z:
                        Handler h = getWindow().getDecorView().getHandler();
                        if (h != null) {
                            h.postDelayed(toggleGrab, 250);
                        }
                        break;

                    // Quit
                    case KeyEvent.KEYCODE_Q:
                        finish();
                        break;

                    // Toggle cursor visibility
                    case KeyEvent.KEYCODE_C:
                        if (!grabbedInput) {
                            inputCaptureProvider.enableCapture();
                            grabbedInput = true;
                        }
                        cursorVisible = !cursorVisible;
                        if (cursorVisible) {
                            inputCaptureProvider.showCursor();
                        } else {
                            inputCaptureProvider.hideCursor();
                        }
                        break;

                    default:
                        break;
                }

                // Reset special key state
                specialKeyCode = KeyEvent.KEYCODE_UNKNOWN;
                waitingForAllModifiersUp = false;
            }
        }
        // Check if Ctrl+Alt+Shift is down when a non-modifier key is pressed
        else if ((modifierFlags & (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT)) ==
                (KeyboardPacket.MODIFIER_CTRL | KeyboardPacket.MODIFIER_ALT | KeyboardPacket.MODIFIER_SHIFT) &&
                (down && nonModifierKeyCode != KeyEvent.KEYCODE_UNKNOWN)) {
            switch (androidKeyCode) {
                case KeyEvent.KEYCODE_Z:
                case KeyEvent.KEYCODE_Q:
                case KeyEvent.KEYCODE_C:
                    // Remember that a special key combo was activated, so we can consume all key
                    // events until the modifiers come up
                    specialKeyCode = androidKeyCode;
                    waitingForAllModifiersUp = true;
                    return true;

                default:
                    // This isn't a special combo that we consume on the client side
                    return false;
            }
        }

        // Not a special combo
        return false;
    }

    // We cannot simply use modifierFlags for all key event processing, because
    // some IMEs will not generate real key events for pressing Shift. Instead
    // they will simply send key events with isShiftPressed() returning true,
    // and we will need to send the modifier flag ourselves.
    private byte getModifierState(KeyEvent event) {
        // Start with the global modifier state to ensure we cover the case
        // detailed in https://github.com/moonlight-stream/moonlight-android/issues/840
        byte modifier = getModifierState();
        if (event.isShiftPressed()) {
            modifier |= KeyboardPacket.MODIFIER_SHIFT;
        }
        if (event.isCtrlPressed()) {
            modifier |= KeyboardPacket.MODIFIER_CTRL;
        }
        if (event.isAltPressed()) {
            modifier |= KeyboardPacket.MODIFIER_ALT;
        }
        if (event.isMetaPressed()) {
            modifier |= KeyboardPacket.MODIFIER_META;
        }
        return modifier;
    }

    private byte getModifierState() {
        return (byte) modifierFlags;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKeyDown(event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean handleKeyDown(KeyEvent event) {
        // Pass-through navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click. This event WILL repeat if
        // the right mouse button is held down, so we ignore those.
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig.mouseNavButtons) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        boolean handled = false;

        if (ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonDown(event);
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            // Let this method take duplicate key down events
            if (handleSpecialKeys(event.getKeyCode(), true)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            // We'll send it as a raw key event if we have a key mapping, otherwise we'll send it as UTF-8 text (if it's a printable character).
            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getDeviceId());
            if (translated == 0) {
                // Make sure it has a valid Unicode representation and it's not a dead character
                // (which we don't support). If those are true, we can send it as UTF-8 text.
                //
                // NB: We need to be sure this happens before the getRepeatCount() check because
                // UTF-8 events don't auto-repeat on the host side.
                int unicodeChar = event.getUnicodeChar();
                if ((unicodeChar & KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK) != 0) {
                    conn.sendUtf8Text(""+(char)unicodeChar);
                    return true;
                }

                return false;
            }

            // Eat repeat down events
            if (event.getRepeatCount() > 0) {
                return true;
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, getModifierState(event),
                    keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), event.getDeviceId()) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        }

        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleKeyUp(event) || super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean handleKeyUp(KeyEvent event) {
        if (isPhysicalKeyboardConnected()) {
            // ESC键双击逻辑
            if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                long currentTime = System.currentTimeMillis();
                
                if (currentTime - lastEscPressTime <= ESC_DOUBLE_PRESS_INTERVAL && hasShownEscHint) {
                    // 第二次按ESC，弹出游戏菜单
                    onBackPressed();
                    lastEscPressTime = 0;
                    hasShownEscHint = false;
                    return true; // 消费事件，不发送给主机
                } else {
                    // 第一次按ESC，显示提示但透传给主机
                    Toast.makeText(this, "再次按 ESC 键打开串流菜单", Toast.LENGTH_SHORT).show();
                    lastEscPressTime = currentTime;
                    hasShownEscHint = true;
                }
            }
        }
        // Pass-through virtual navigation keys
        if ((event.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0) {
            return false;
        }

        // Handle a synthetic back button event that some Android OS versions
        // create as a result of a right-click.
        int eventSource = event.getSource();
        if ((eventSource == InputDevice.SOURCE_MOUSE ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) &&
                event.getKeyCode() == KeyEvent.KEYCODE_BACK) {

            // Send the right mouse button event if mouse back and forward
            // are disabled. If they are enabled, handleMotionEvent() will take
            // care of this.
            if (!prefConfig.mouseNavButtons) {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
            }

            // Always return true, otherwise the back press will be propagated
            // up to the parent and finish the activity.
            return true;
        }

        boolean handled = false;
        if (ControllerHandler.isGameControllerDevice(event.getDevice())) {
            // Always try the controller handler first, unless it's an alphanumeric keyboard device.
            // Otherwise, controller handler will eat keyboard d-pad events.
            handled = controllerHandler.handleButtonUp(event);
        }

        // Try the keyboard handler if it wasn't handled as a game controller
        if (!handled) {
            if (handleSpecialKeys(event.getKeyCode(), false)) {
                return true;
            }

            // Pass through keyboard input if we're not grabbing
            if (!grabbedInput) {
                return false;
            }

            short translated = keyboardTranslator.translate(event.getKeyCode(), event.getDeviceId());
            if (translated == 0) {
                // If we sent this event as UTF-8 on key down, also report that it was handled
                // when we get the key up event for it.
                int unicodeChar = event.getUnicodeChar();
                return (unicodeChar & KeyCharacterMap.COMBINING_ACCENT) == 0 && (unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK) != 0;
            }

            conn.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, getModifierState(event),
                    keyboardTranslator.hasNormalizedMapping(event.getKeyCode(), event.getDeviceId()) ? 0 : MoonBridge.SS_KBE_FLAG_NON_NORMALIZED);
        }

        return true;
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        return handleKeyMultiple(event) || super.onKeyMultiple(keyCode, repeatCount, event);
    }

    private boolean handleKeyMultiple(KeyEvent event) {
        // We can receive keys from a software keyboard that don't correspond to any existing
        // KEYCODE value. Android will give those to us as an ACTION_MULTIPLE KeyEvent.
        //
        // Despite the fact that the Android docs say this is unused since API level 29, these
        // events are still sent as of Android 13 for the above case.
        //
        // For other cases of ACTION_MULTIPLE, we will not report those as handled so hopefully
        // they will be passed to us again as regular singular key events.
        if (event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN || event.getCharacters() == null) {
            return false;
        }

        conn.sendUtf8Text(event.getCharacters());
        return true;
    }

    private TouchContext getTouchContext(int actionIndex)
    {
        if (actionIndex < touchContextMap.length) {
            return touchContextMap[actionIndex];
        }
        else {
            return null;
        }
    }

    public TouchContext[] getTouchContextMap() {
        return touchContextMap;
    }

    public TouchContext[] getRelativeTouchContextMap(){
        return  relativeTouchContextMap;
    }

    /**
     * false : RelativeTouchContext
     * true : AbsoluteTouchContext
     */
    public void setTouchMode(boolean enableRelativeTouch){
        for (int i = 0; i < touchContextMap.length; i++) {
            if (enableRelativeTouch) {
                prefConfig.touchscreenTrackpad = true;
                touchContextMap = relativeTouchContextMap;
            }
            else {
                prefConfig.touchscreenTrackpad = false;
                touchContextMap = absoluteTouchContextMap;
            }
        }
    }

    @Override
    public void toggleKeyboard() {
        LimeLog.info("Toggling keyboard overlay");

        // Hack: allows use keyboard by dpad or controller
        streamView.clearFocus();

        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.toggleSoftInput(0, 0);
    }

    private byte getLiTouchTypeFromEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                return MoonBridge.LI_TOUCH_EVENT_DOWN;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if ((event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    return MoonBridge.LI_TOUCH_EVENT_CANCEL;
                }
                else {
                    return MoonBridge.LI_TOUCH_EVENT_UP;
                }

            case MotionEvent.ACTION_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_MOVE;

            case MotionEvent.ACTION_CANCEL:
                // ACTION_CANCEL applies to *all* pointers in the gesture, so it maps to CANCEL_ALL
                // rather than CANCEL. For a single pointer cancellation, that's indicated via
                // FLAG_CANCELED on a ACTION_POINTER_UP.
                // https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi
                return MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL;

            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                return MoonBridge.LI_TOUCH_EVENT_HOVER;

            case MotionEvent.ACTION_HOVER_EXIT:
                return MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE;

            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY;

            default:
               return -1;
        }
    }


    private float[] getStreamViewRelativeNormalizedXY(View view, MotionEvent event, int pointerIndex) {
        float normalizedX;
        float normalizedY;
        if(prefConfig.enableEnhancedTouch){
            // Coords are replaced by NativeTouchContext here.
            NativeTouchContext.Pointer pointer = nativeTouchPointerMap.get(event.getPointerId(pointerIndex));
            if(pointer != null) {
                float targetCoords[] = pointer.XYCoordSelector(); // decides to passthrough or manipulate coords.
                normalizedX = targetCoords[0];
                normalizedY = targetCoords[1];
            }
            else{
                normalizedX = 0f; //in this case (pointer == null), pointers are already all up.
                normalizedY = 0f;
            }
        }
        else{
            normalizedX = event.getX(pointerIndex);
            normalizedY = event.getY(pointerIndex);
        }


        // For the containing background view, we must subtract the origin
        // of the StreamView to get video-relative coordinates.
        if (view != streamView) {
            normalizedX -= streamView.getX();
            normalizedY -= streamView.getY();
        }

        normalizedX = Math.max(normalizedX, 0.0f);
        normalizedY = Math.max(normalizedY, 0.0f);

        normalizedX = Math.min(normalizedX, streamView.getWidth());
        normalizedY = Math.min(normalizedY, streamView.getHeight());

        normalizedX /= streamView.getWidth();
        normalizedY /= streamView.getHeight();

        return new float[] { normalizedX, normalizedY };
    }

    private static float normalizeValueInRange(float value, InputDevice.MotionRange range) {
        return (value - range.getMin()) / range.getRange();
    }

    private static float getPressureOrDistance(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                // Hover events report distance
                if (dev != null) {
                    InputDevice.MotionRange distanceRange = dev.getMotionRange(MotionEvent.AXIS_DISTANCE, event.getSource());
                    if (distanceRange != null) {
                        return normalizeValueInRange(event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex), distanceRange);
                    }
                }
                return 0.0f;

            default:
                // Other events report pressure
                return event.getPressure(pointerIndex);
        }
    }

    private static short getRotationDegrees(MotionEvent event, int pointerIndex) {
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) != null) {
                short rotationDegrees = (short) Math.toDegrees(event.getOrientation(pointerIndex));
                if (rotationDegrees < 0) {
                    rotationDegrees += 360;
                }
                return rotationDegrees;
            }
        }
        return MoonBridge.LI_ROT_UNKNOWN;
    }

    private static float[] polarToCartesian(float r, float theta) {
        return new float[] { (float)(r * Math.cos(theta)), (float)(r * Math.sin(theta)) };
    }

    private static float cartesianToR(float[] point) {
        return (float)Math.sqrt(Math.pow(point[0], 2) + Math.pow(point[1], 2));
    }

    private float[] getStreamViewNormalizedContactArea(MotionEvent event, int pointerIndex) {
        float orientation;

        // If the orientation is unknown, we'll just assume it's at a 45 degree angle and scale it by
        // X and Y scaling factors evenly.
        if (event.getDevice() == null || event.getDevice().getMotionRange(MotionEvent.AXIS_ORIENTATION, event.getSource()) == null) {
            orientation = (float)(Math.PI / 4);
        }
        else {
            orientation = event.getOrientation(pointerIndex);
        }

        float contactAreaMajor, contactAreaMinor;
        switch (event.getActionMasked()) {
            // Hover events report the tool size
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_EXIT:
                contactAreaMajor = event.getToolMajor(pointerIndex);
                contactAreaMinor = event.getToolMinor(pointerIndex);
                break;

            // Other events report contact area
            default:
                contactAreaMajor = event.getTouchMajor(pointerIndex);
                contactAreaMinor = event.getTouchMinor(pointerIndex);
                break;
        }

        // The contact area major axis is parallel to the orientation, so we simply convert
        // polar to cartesian coordinates using the orientation as theta.
        float[] contactAreaMajorCartesian = polarToCartesian(contactAreaMajor, orientation);

        // The contact area minor axis is perpendicular to the contact area major axis (and thus
        // the orientation), so rotate the orientation angle by 90 degrees.
        float[] contactAreaMinorCartesian = polarToCartesian(contactAreaMinor, (float)(orientation + (Math.PI / 2)));

        // Normalize the contact area to the stream view size
        contactAreaMajorCartesian[0] = Math.min(Math.abs(contactAreaMajorCartesian[0]), streamView.getWidth()) / streamView.getWidth();
        contactAreaMinorCartesian[0] = Math.min(Math.abs(contactAreaMinorCartesian[0]), streamView.getWidth()) / streamView.getWidth();
        contactAreaMajorCartesian[1] = Math.min(Math.abs(contactAreaMajorCartesian[1]), streamView.getHeight()) / streamView.getHeight();
        contactAreaMinorCartesian[1] = Math.min(Math.abs(contactAreaMinorCartesian[1]), streamView.getHeight()) / streamView.getHeight();

        // Convert the normalized values back into polar coordinates
        return new float[] { cartesianToR(contactAreaMajorCartesian), cartesianToR(contactAreaMinorCartesian) };
    }

    private boolean sendPenEventForPointer(View view, MotionEvent event, byte eventType, byte toolType, int pointerIndex) {
        byte penButtons = 0;
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_PRIMARY;
        }
        if ((event.getButtonState() & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            penButtons |= MoonBridge.LI_PEN_BUTTON_SECONDARY;
        }

        byte tiltDegrees = MoonBridge.LI_TILT_UNKNOWN;
        InputDevice dev = event.getDevice();
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_TILT, event.getSource()) != null) {
                tiltDegrees = (byte)Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex));
            }
        }

        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex);
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return conn.sendPenEvent(eventType, toolType, penButtons,
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex), tiltDegrees) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private static byte convertToolTypeToStylusToolType(MotionEvent event, int pointerIndex) {
        switch (event.getToolType(pointerIndex)) {
            case MotionEvent.TOOL_TYPE_ERASER:
                return MoonBridge.LI_TOOL_TYPE_ERASER;
            case MotionEvent.TOOL_TYPE_STYLUS:
                return MoonBridge.LI_TOOL_TYPE_PEN;
            default:
                return MoonBridge.LI_TOOL_TYPE_UNKNOWN;
        }
    }

    private boolean trySendPenEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            boolean handledStylusEvent = false;
            for (int i = 0; i < event.getPointerCount(); i++) {
                byte toolType = convertToolTypeToStylusToolType(event, i);
                if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                    // Not a stylus pointer, so skip it
                    continue;
                }
                else {
                    // This pointer is a stylus, so we'll report that we handled this event
                    handledStylusEvent = true;
                }

                if (!sendPenEventForPointer(view, event, eventType, toolType, i)) {
                    // Pen events aren't supported by the host
                    return false;
                }
            }
            return handledStylusEvent;
        }
        else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendPenEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, MoonBridge.LI_TOOL_TYPE_UNKNOWN, (byte)0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN, MoonBridge.LI_TILT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        }
        else {
            // Up, Down, and Hover events are specific to the action index
            byte toolType = convertToolTypeToStylusToolType(event, event.getActionIndex());
            if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) {
                // Not a stylus event
                return false;
            }
            return sendPenEventForPointer(view, event, eventType, toolType, event.getActionIndex());
        }
    }

    private boolean sendTouchEventForPointer(View view, MotionEvent event, byte eventType, int pointerIndex) {
        float[] normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex); //normalized Coords就是坐标占长或宽的比例，最小0，最大1
        float[] normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex);
        return conn.sendTouchEvent(eventType, event.getPointerId(pointerIndex),
                normalizedCoords[0], normalizedCoords[1],
                getPressureOrDistance(event, pointerIndex),
                normalizedContactArea[0], normalizedContactArea[1],
                getRotationDegrees(event, pointerIndex)) != MoonBridge.LI_ERR_UNSUPPORTED;
    }

    private boolean trySendTouchEvent(View view, MotionEvent event) {
        byte eventType = getLiTouchTypeFromEvent(event);
        if (eventType < 0) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // Move events may impact all active pointers
            if (prefConfig.enableEnhancedTouch) {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    Objects.requireNonNull(nativeTouchPointerMap.get(event.getPointerId(i))).updatePointerCoords(event, i); // update pointer coords in the map.
                    if (!sendTouchEventForPointer(view, event, eventType, i)) {
                        return false;
                    }
                }
            }
            else {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    if (!sendTouchEventForPointer(view, event, eventType, i)) {
                        return false;
                    }
                }
            }
            return true;
        }
        else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            // Cancel impacts all active pointers
            return conn.sendTouchEvent(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
                    0, 0, 0, 0, 0,
                    MoonBridge.LI_ROT_UNKNOWN) != MoonBridge.LI_ERR_UNSUPPORTED;
        }
        else {
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_POINTER_DOWN:
                multiFingerTapChecker(event);
                case MotionEvent.ACTION_DOWN: // first & following finger down.
                    if(prefConfig.enableEnhancedTouch) {
                        NativeTouchContext.Pointer pointer = new NativeTouchContext.Pointer(event); //create a Pointer Instance for new touch pointer, put it into the map.
                        nativeTouchPointerMap.put(pointer.getPointerId(), pointer);
                    }
                    break;
                case MotionEvent.ACTION_UP: // all fingers up
                    // toggle keyboard when all fingers lift up, just like how it works in trackpad mode.
                    if (event.getEventTime() - multiFingerDownTime < MULTI_FINGER_TAP_THRESHOLD) {
                        toggleKeyboard();
                    }
                case MotionEvent.ACTION_POINTER_UP:
                    if (prefConfig.enableEnhancedTouch) {
                        nativeTouchPointerMap.remove(event.getPointerId(event.getActionIndex()));
                    }
                    break;
            }
            // Up, Down, and Hover events are specific to the action index
            return sendTouchEventForPointer(view, event, eventType, event.getActionIndex());
        }
    }

    private void multiFingerTapChecker (MotionEvent event) {
        if (event.getPointerCount() == prefConfig.nativeTouchFingersToToggleKeyboard) {
            // number of fingers to tap is defined by prefConfig.nativeTouchFingersToToggleKeyboard, configurable from 3 to 10, and -1(disabled) in menu.

            // Cancel the first and second touches to avoid
            // erroneous events
            // for (TouchContext aTouchContext : touchContextMap) {
            //    aTouchContext.cancelTouch();
            // }
            multiFingerDownTime = event.getEventTime();
        }
    }

    // Returns true if the event was consumed
    // NB: View is only present if called from a view callback
    private boolean handleMotionEvent(View view, MotionEvent event) {
        // Pass through mouse/touch/joystick input if we're not grabbing

        if (!grabbedInput) {
            return false;
        }

        int eventSource = event.getSource();
        int deviceSources = event.getDevice() != null ? event.getDevice().getSources() : 0;
        if ((eventSource & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) { //手柄所属条件
            if (controllerHandler.handleMotionEvent(event)) {
                return true;
            }
        }
        else if ((deviceSources & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && controllerHandler.tryHandleTouchpadEvent(event)) {
            return true;
        }
        else if ((eventSource & InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                 (eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 ||
                 eventSource == InputDevice.SOURCE_MOUSE_RELATIVE)
        {
            // This case is for mice and non-finger touch devices, 非手指触控功能所属判断条件
            if (eventSource == InputDevice.SOURCE_MOUSE ||
                    (eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0 || // SOURCE_TOUCHPAD虚拟手柄
                    eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
                    (event.getPointerCount() >= 1 &&
                            (event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
                                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                                    event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)) ||
                    eventSource == 12290) // 12290 = Samsung DeX mode desktop mouse
            {
                int buttonState = event.getButtonState();
                int changedButtons = buttonState ^ lastButtonState;

                // The DeX touchpad on the Fold 4 sends proper right click events using BUTTON_SECONDARY,
                // but doesn't send BUTTON_PRIMARY for a regular click. Instead it sends ACTION_DOWN/UP,
                // so we need to fix that up to look like a sane input event to process it correctly.
                if (eventSource == 12290) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        buttonState |= MotionEvent.BUTTON_PRIMARY;
                    }
                    else if (event.getAction() == MotionEvent.ACTION_UP) {
                        buttonState &= ~MotionEvent.BUTTON_PRIMARY;
                    }
                    else {
                        // We may be faking the primary button down from a previous event,
                        // so be sure to add that bit back into the button state.
                        buttonState |= (lastButtonState & MotionEvent.BUTTON_PRIMARY);
                    }

                    changedButtons = buttonState ^ lastButtonState;
                }

                // Ignore mouse input if we're not capturing from our input source
                if (!inputCaptureProvider.isCapturingActive()) {
                    // We return true here because otherwise the events may end up causing
                    // Android to synthesize d-pad events.
                    return true;
                }

                // Always update the position before sending any button events. If we're
                // dealing with a stylus without hover support, our position might be
                // significantly different than before.
                if (inputCaptureProvider.eventHasRelativeMouseAxes(event)) {
                    // Send the deltas straight from the motion event
                    short deltaX = (short)inputCaptureProvider.getRelativeAxisX(event);
                    short deltaY = (short)inputCaptureProvider.getRelativeAxisY(event);

                    if (deltaX != 0 || deltaY != 0) {
                        if (prefConfig.absoluteMouseMode) {
                            // NB: view may be null, but we can unconditionally use streamView because we don't need to adjust
                            // relative axis deltas for the position of the streamView within the parent's coordinate system.
                            conn.sendMouseMoveAsMousePosition(deltaX, deltaY, (short)streamView.getWidth(), (short)streamView.getHeight());
                        }
                        else {
                            conn.sendMouseMove(deltaX, deltaY);
                        }
                    }
                }
                else if ((eventSource & InputDevice.SOURCE_CLASS_POSITION) != 0) {
                    // If this input device is not associated with the view itself (like a trackpad),
                    // we'll convert the device-specific coordinates to use to send the cursor position.
                    // This really isn't ideal but it's probably better than nothing.
                    //
                    // Trackpad on newer versions of Android (Oreo and later) should be caught by the
                    // relative axes case above. If we get here, we're on an older version that doesn't
                    // support pointer capture.
                    InputDevice device = event.getDevice();
                    if (device != null) {
                        InputDevice.MotionRange xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource);
                        InputDevice.MotionRange yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource);

                        // All touchpads coordinate planes should start at (0, 0)
                        if (xRange != null && yRange != null && xRange.getMin() == 0 && yRange.getMin() == 0) {
                            int xMax = (int)xRange.getMax();
                            int yMax = (int)yRange.getMax();

                            // Touchpads must be smaller than (65535, 65535)
                            if (xMax <= Short.MAX_VALUE && yMax <= Short.MAX_VALUE) {
                                conn.sendMousePosition((short)event.getX(), (short)event.getY(),
                                                       (short)xMax, (short)yMax);
                            }
                        }
                    }
                }
                else if (view != null && trySendPenEvent(view, event)) {
                    // If our host supports pen events, send it directly
                    return true;
                }
                else if (view != null) {
                    // Otherwise send absolute position based on the view for SOURCE_CLASS_POINTER
                    updateMousePosition(view, event);
                }

                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                    // Send the vertical scroll packet
                    conn.sendMouseHighResScroll((short)(event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120));
                    conn.sendMouseHighResHScroll((short)(event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120));
                }

                if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((buttonState & MotionEvent.BUTTON_PRIMARY) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                    }
                }

                // Mouse secondary or stylus primary is right click (stylus down is left click)
                if ((changedButtons & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                    if ((buttonState & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                    }
                }

                // Mouse tertiary or stylus secondary is middle click
                if ((changedButtons & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                    if ((buttonState & (MotionEvent.BUTTON_TERTIARY | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                    else {
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                    }
                }

                if (prefConfig.mouseNavButtons) {
                    if ((changedButtons & MotionEvent.BUTTON_BACK) != 0) {
                        if ((buttonState & MotionEvent.BUTTON_BACK) != 0) {
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1);
                        }
                        else {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1);
                        }
                    }

                    if ((changedButtons & MotionEvent.BUTTON_FORWARD) != 0) {
                        if ((buttonState & MotionEvent.BUTTON_FORWARD) != 0) {
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2);
                        }
                        else {
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2);
                        }
                    }
                }

                // Handle stylus presses
                if (event.getPointerCount() == 1 && event.getActionIndex() == 0) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchDownTime = event.getEventTime();
                            lastAbsTouchDownX = event.getX(0);
                            lastAbsTouchDownY = event.getY(0);

                            // Stylus is left click
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchDownTime = event.getEventTime();
                            lastAbsTouchDownX = event.getX(0);
                            lastAbsTouchDownY = event.getY(0);

                            // Eraser is right click
                            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    }
                    else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchUpTime = event.getEventTime();
                            lastAbsTouchUpX = event.getX(0);
                            lastAbsTouchUpY = event.getY(0);

                            // Stylus is left click
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchUpTime = event.getEventTime();
                            lastAbsTouchUpX = event.getX(0);
                            lastAbsTouchUpY = event.getY(0);

                            // Eraser is right click
                            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                        }
                    }
                }

                lastButtonState = buttonState;
            }
            // This case is for fingers
            else  //abs touch 和 屏幕虚拟手柄所属的判断条件
            {
                // TODO: Re-enable native touch when have a better solution for handling
                // cancelled touches from Android gestures and 3 finger taps to activate
                // the software keyboard.
                // 调整一下native touch passthrough的代码顺序
                if (!prefConfig.touchscreenTrackpad && prefConfig.enableEnhancedTouch && trySendTouchEvent(view, event)) {
                    // If this host supports touch events and absolute touch is enabled,
                    // send it directly as a touch event.
                    return true;
                }

                if (virtualController != null &&
                        (virtualController.getControllerMode() == VirtualController.ControllerMode.MoveButtons ||
                         virtualController.getControllerMode() == VirtualController.ControllerMode.ResizeButtons)) {
                    // Ignore presses when the virtual controller is being configured
                    return true;
                }

                // If this is the parent view, we'll offset our coordinates to appear as if they
                // are relative to the StreamView like our StreamView touch events are.
                float xOffset, yOffset;
                if (view != streamView && !prefConfig.touchscreenTrackpad) {
                    xOffset = -streamView.getX();
                    yOffset = -streamView.getY();
                }
                else {
                    xOffset = 0.f;
                    yOffset = 0.f;
                }

                int actionIndex = event.getActionIndex();

                int eventX = (int)(event.getX(actionIndex) + xOffset);
                int eventY = (int)(event.getY(actionIndex) + yOffset);

                // Special handling for 3 finger gesture
                if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN &&
                        event.getPointerCount() == 3) {
                    // Three fingers down
                    multiFingerDownTime = event.getEventTime();

                    // Cancel the first and second touches to avoid
                    // erroneous events
                    for (TouchContext aTouchContext : touchContextMap) {
                        aTouchContext.cancelTouch();
                    }

                    return true;
                }


                // TODO: Re-enable native touch when have a better solution for handling
                // cancelled touches from Android gestures and 3 finger taps to activate
                // the software keyboard.
                // if (!prefConfig.touchscreenTrackpad && trySendTouchEvent(view, event)) {
                //     // If this host supports touch events and absolute touch is enabled,
                //     // send it directly as a touch event.
                //     return true;
                // }

                TouchContext context = getTouchContext(actionIndex);
                if (context == null) {
                    return false;
                }

                switch (event.getActionMasked())
                {
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_DOWN:
                    for (TouchContext touchContext : touchContextMap) {
                        touchContext.setPointerCount(event.getPointerCount());
                    }
                    context.touchDownEvent(eventX, eventY, event.getEventTime(), true);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP:
                    if (event.getPointerCount() == 1 &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (event.getFlags() & MotionEvent.FLAG_CANCELED) == 0)) {
                        // All fingers up
                        if (event.getEventTime() - multiFingerDownTime < MULTI_FINGER_TAP_THRESHOLD) {
                            // This is a 3 finger tap to bring up the keyboard
                            // multiFingerDownTime, previously threeFingerDowntime, is also used in native-touch for keyboard toggle.
                            toggleKeyboard();
                            return true;
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                        context.cancelTouch();
                    }
                    else {
                        context.touchUpEvent(eventX, eventY, event.getEventTime());
                    }

                    for (TouchContext touchContext : touchContextMap) {
                        touchContext.setPointerCount(event.getPointerCount() - 1);
                    }
                    if (actionIndex == 0 && event.getPointerCount() > 1 && !context.isCancelled()) {
                        // The original secondary touch now becomes primary
                        context.touchDownEvent(
                                (int)(event.getX(1) + xOffset),
                                (int)(event.getY(1) + yOffset),
                                event.getEventTime(), false);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    // ACTION_MOVE is special because it always has actionIndex == 0
                    // We'll call the move handlers for all indexes manually

                    // First process the historical events
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        for (TouchContext aTouchContextMap : touchContextMap) {
                            if (aTouchContextMap.getActionIndex() < event.getPointerCount())
                            {
                                aTouchContextMap.touchMoveEvent(
                                        (int)(event.getHistoricalX(aTouchContextMap.getActionIndex(), i) + xOffset),
                                        (int)(event.getHistoricalY(aTouchContextMap.getActionIndex(), i) + yOffset),
                                        event.getHistoricalEventTime(i));
                            }
                        }
                    }

                    // Now process the current values
                    for (TouchContext aTouchContextMap : touchContextMap) {
                        if (aTouchContextMap.getActionIndex() < event.getPointerCount())
                        {
                            aTouchContextMap.touchMoveEvent(
                                    (int)(event.getX(aTouchContextMap.getActionIndex()) + xOffset),
                                    (int)(event.getY(aTouchContextMap.getActionIndex()) + yOffset),
                                    event.getEventTime());
                        }
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    for (TouchContext aTouchContext : touchContextMap) {
                        aTouchContext.cancelTouch();
                        aTouchContext.setPointerCount(0);
                    }
                    break;
                default:
                    return false;
                }
            }

            // Handled a known source
            return true;
        }

        // Unknown class
        return false;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return handleMotionEvent(null, event) || super.onGenericMotionEvent(event);

    }

    private void updateMousePosition(View touchedView, MotionEvent event) {
        // X and Y are already relative to the provided view object
        float eventX, eventY;

        // For our StreamView itself, we can use the coordinates unmodified.
        if (touchedView == streamView) {
            eventX = event.getX(0);
            eventY = event.getY(0);
        }
        else {
            // For the containing background view, we must subtract the origin
            // of the StreamView to get video-relative coordinates.
            eventX = event.getX(0) - streamView.getX();
            eventY = event.getY(0) - streamView.getY();
        }

        if (event.getPointerCount() == 1 && event.getActionIndex() == 0 &&
                (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS))
        {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_EXIT:
                case MotionEvent.ACTION_HOVER_MOVE:
                    if (event.getEventTime() - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchUpX, 2) + Math.pow(eventY - lastAbsTouchUpY, 2)) <= STYLUS_UP_DEAD_ZONE_RADIUS) {
                        // Enforce a small deadzone between touch up and hover or touch down to allow more precise double-clicking
                        return;
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (event.getEventTime() - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY &&
                            Math.sqrt(Math.pow(eventX - lastAbsTouchDownX, 2) + Math.pow(eventY - lastAbsTouchDownY, 2)) <= STYLUS_DOWN_DEAD_ZONE_RADIUS) {
                        // Enforce a small deadzone between touch down and move or touch up to allow more precise double-clicking
                        return;
                    }
                    break;
            }
        }

        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        eventX = Math.min(Math.max(eventX, 0), streamView.getWidth());
        eventY = Math.min(Math.max(eventY, 0), streamView.getHeight());

        conn.sendMousePosition((short)eventX, (short)eventY, (short)streamView.getWidth(), (short)streamView.getHeight());
    }

    @Override
    public boolean onGenericMotion(View view, MotionEvent event) {
        return handleMotionEvent(view, event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // Tell the OS not to buffer input events for us
            //
            // NB: This is still needed even when we call the newer requestUnbufferedDispatch()!
            // Add a configuration to allow view.requestUnbufferedDispatch to be disabled.
            if(!prefConfig.syncTouchEventWithDisplay) {
                view.requestUnbufferedDispatch(event);
            }
        }
        return handleMotionEvent(view, event); //Y700平板上, onTouch的调用频率为120Hz
    }

    @Override
    public void stageStarting(final String stage) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (spinner != null) {
                    spinner.setMessage(getResources().getString(R.string.conn_starting) + " " + stage);
                }
            }
        });
    }

    @Override
    public void stageComplete(String stage) {
    }

    private void stopConnection() {
        if (connecting || connected) {
            connecting = connected = false;
            updatePipAutoEnter();

            controllerHandler.stop();

            // Update GameManager state to indicate we're no longer in game
            UiHelper.notifyStreamEnded(this);

            // Stop may take a few hundred ms to do some network I/O to tell
            // the server we're going away and clean up. Let it run in a separate
            // thread to keep things smooth for the UI. Inside moonlight-common,
            // we prevent another thread from starting a connection before and
            // during the process of stopping this one.
            new Thread() {
                public void run() {
                    conn.stop();
                }
            }.start();
        }
    }

    @Override
    public void stageFailed(final String stage, final int portFlags, final int errorCode) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        final int portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443, portFlags);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (spinner != null) {
                    spinner.dismiss();
                    spinner = null;
                }

                if (!displayedFailureDialog) {
                    displayedFailureDialog = true;
                    LimeLog.severe(stage + " failed: " + errorCode);

                    // If video initialization failed and the surface is still valid, display extra information for the user
                    if (stage.contains("video") && streamView.getHolder().getSurface().isValid()) {
                        Toast.makeText(Game.this, getResources().getText(R.string.video_decoder_init_failed), Toast.LENGTH_LONG).show();
                    }

                    String dialogText = getResources().getString(R.string.conn_error_msg) + " " + stage +" (error "+errorCode+")";

                    if (portFlags != 0) {
                        dialogText += "\n\n" + getResources().getString(R.string.check_ports_msg) + "\n" +
                                MoonBridge.stringifyPortFlags(portFlags, "\n");
                    }

                    if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0)  {
                        dialogText += "\n\n" + getResources().getString(R.string.nettest_text_blocked);
                    }

                    Dialog.displayDialog(Game.this, getResources().getString(R.string.conn_error_title), dialogText, true);
                }
            }
        });
    }

    @Override
    public void connectionTerminated(final int errorCode) {
        // Perform a connection test if the failure could be due to a blocked port
        // This does network I/O, so don't do it on the main thread.
        final int portFlags = MoonBridge.getPortFlagsFromTerminationErrorCode(errorCode);
        final int portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER,443, portFlags);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Let the display go to sleep now
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                // Stop processing controller input
                controllerHandler.stop();

                // Ungrab input
                setInputGrabState(false);

                if (!displayedFailureDialog) {
                    displayedFailureDialog = true;
                    LimeLog.severe("Connection terminated: " + errorCode);
                    stopConnection();

                    // Display the error dialog if it was an unexpected termination.
                    // Otherwise, just finish the activity immediately.
                    if (errorCode != MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
                        String message;

                        if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0) {
                            // If we got a blocked result, that supersedes any other error message
                            message = getResources().getString(R.string.nettest_text_blocked);
                        }
                        else {
                            switch (errorCode) {
                                case MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC:
                                    message = getResources().getString(R.string.no_video_received_error);
                                    break;

                                case MoonBridge.ML_ERROR_NO_VIDEO_FRAME:
                                    message = getResources().getString(R.string.no_frame_received_error);
                                    break;

                                case MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION:
                                case MoonBridge.ML_ERROR_PROTECTED_CONTENT:
                                    message = getResources().getString(R.string.early_termination_error);
                                    break;

                                case MoonBridge.ML_ERROR_FRAME_CONVERSION:
                                    message = getResources().getString(R.string.frame_conversion_error);
                                    break;

                                default:
                                    String errorCodeString;
                                    // We'll assume large errors are hex values
                                    if (Math.abs(errorCode) > 1000) {
                                        errorCodeString = Integer.toHexString(errorCode);
                                    }
                                    else {
                                        errorCodeString = Integer.toString(errorCode);
                                    }
                                    message = getResources().getString(R.string.conn_terminated_msg) + "\n\n" +
                                            getResources().getString(R.string.error_code_prefix) + " " + errorCodeString;
                                    break;
                            }
                        }

                        if (portFlags != 0) {
                            message += "\n\n" + getResources().getString(R.string.check_ports_msg) + "\n" +
                                    MoonBridge.stringifyPortFlags(portFlags, "\n");
                        }

                        Dialog.displayDialog(Game.this, getResources().getString(R.string.conn_terminated_title),
                                message, true);
                    }
                    else {
                        finish();
                    }
                }
            }
        });
    }

    @Override
    public void connectionStatusUpdate(final int connectionStatus) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (prefConfig.disableWarnings) {
                    return;
                }

                if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
                    if (prefConfig.bitrate > 5000) {
                        notificationOverlayView.setText(getResources().getString(R.string.slow_connection_msg));
                    }
                    else {
                        notificationOverlayView.setText(getResources().getString(R.string.poor_connection_msg));
                    }

                    requestedNotificationOverlayVisibility = View.VISIBLE;
                }
                else if (connectionStatus == MoonBridge.CONN_STATUS_OKAY) {
                    requestedNotificationOverlayVisibility = View.GONE;
                }

                if (!isHidingOverlays) {
                    notificationOverlayView.setVisibility(requestedNotificationOverlayVisibility);
                }
            }
        });
    }

    @Override
    public void connectionStarted() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (spinner != null) {
                    spinner.dismiss();
                    spinner = null;
                }

                connected = true;
                connecting = false;
                updatePipAutoEnter();

                // Hide the mouse cursor now after a short delay.
                // Doing it before dismissing the spinner seems to be undone
                // when the spinner gets displayed. On Android Q, even now
                // is too early to capture. We will delay a second to allow
                // the spinner to dismiss before capturing.
                Handler h = new Handler();
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        setInputGrabState(true);
                    }
                }, 500);

                // Keep the display on
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                // Update GameManager state to indicate we're in game
                UiHelper.notifyStreamConnected(Game.this);

                hideSystemUi(1000);
            }
        });

        // Report this shortcut being used (off the main thread to prevent ANRs)
        ComputerDetails computer = new ComputerDetails();
        computer.name = pcName;
        computer.uuid = Game.this.getIntent().getStringExtra(EXTRA_PC_UUID);
        ShortcutHelper shortcutHelper = new ShortcutHelper(this);
        shortcutHelper.reportComputerShortcutUsed(computer);
        if (appName != null) {
            // This may be null if launched from the "Resume Session" PC context menu item
            shortcutHelper.reportGameLaunched(computer, app);
        }

        // 检查是否启用了HDR并主动设置初始状态
        // 这解决了首次连接时setHdrMode没有被调用的问题
        boolean appSupportsHdr = Game.this.getIntent().getBooleanExtra(EXTRA_APP_HDR, false);
        if (appSupportsHdr && prefConfig.enableHdr) {
            setHdrMode(true, null);
        }
    }

    @Override
    public void displayMessage(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void displayTransientMessage(final String message) {
        if (!prefConfig.disableWarnings) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(Game.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    public void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        LimeLog.info(String.format((Locale)null, "Rumble on gamepad %d: %04x %04x", controllerNumber, lowFreqMotor, highFreqMotor));
        if (controllerManager != null){
            controllerManager.getElementController().gameVibrator(lowFreqMotor,highFreqMotor);
        }
        controllerHandler.handleRumble(controllerNumber, lowFreqMotor, highFreqMotor);
    }

    @Override
    public void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
        LimeLog.info(String.format((Locale)null, "Rumble on gamepad triggers %d: %04x %04x", controllerNumber, leftTrigger, rightTrigger));

        controllerHandler.handleRumbleTriggers(controllerNumber, leftTrigger, rightTrigger);
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        LimeLog.info("Display HDR mode: " + (enabled ? "enabled" : "disabled"));
        decoderRenderer.setHdrMode(enabled, hdrMetadata);

        // 通知系统HDR内容状态，特别是对ColorOS系统
        if (UiHelper.isColorOS()) {
            notifySystemHdrStatus(enabled);
        }
    }

    private void notifySystemHdrStatus(boolean hdrEnabled) {
        runOnUiThread(() -> {
            try {
                // 通过Window设置色彩模式
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (hdrEnabled) {
                        getWindow().setColorMode(ActivityInfo.COLOR_MODE_HDR);
                    } else {
                        getWindow().setColorMode(ActivityInfo.COLOR_MODE_DEFAULT);
                    }
                }

                // 通过WindowManager.LayoutParams设置亮度
                WindowManager.LayoutParams params = getWindow().getAttributes();
                if (hdrEnabled) {
                    // 强制高亮度模式
                    params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
                    // 设置窗口标志以支持HDR
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                    }
                } else {
                    params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                }
                getWindow().setAttributes(params);

                LimeLog.info("ColorOS HDR notification: Window color mode and brightness updated for HDR " +
                        (hdrEnabled ? "enabled" : "disabled"));

            } catch (Exception e) {
                LimeLog.warning("Failed to notify ColorOS system HDR status: " + e.getMessage());
            }
        });
    }

    @Override
    public void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz) {
        controllerHandler.handleSetMotionEventState(controllerNumber, motionType, reportRateHz);
    }

    @Override
    public void setControllerLED(short controllerNumber, byte r, byte g, byte b) {
        controllerHandler.handleSetControllerLED(controllerNumber, r, g, b);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (!surfaceCreated) {
            throw new IllegalStateException("Surface changed before creation!");
        }

        if (!attemptedConnection) {
            attemptedConnection = true;

            // Update GameManager state to indicate we're "loading" while connecting
            UiHelper.notifyStreamConnecting(Game.this);

            decoderRenderer.setRenderTarget(holder);
            conn.start(new AndroidAudioRenderer(Game.this, prefConfig.enableAudioFx),
                    decoderRenderer, Game.this);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        float desiredFrameRate;

        surfaceCreated = true;

        // Android will pick the lowest matching refresh rate for a given frame rate value, so we want
        // to report the true FPS value if refresh rate reduction is enabled. We also report the true
        // FPS value if there's no suitable matching refresh rate. In that case, Android could try to
        // select a lower refresh rate that avoids uneven pull-down (ex: 30 Hz for a 60 FPS stream on
        // a display that maxes out at 50 Hz).
        if (mayReduceRefreshRate() || desiredRefreshRate < prefConfig.fps) {
            desiredFrameRate = prefConfig.fps;
        }
        else {
            // Otherwise, we will pretend that our frame rate matches the refresh rate we picked in
            // prepareDisplayForRendering(). This will usually be the highest refresh rate that our
            // frame rate evenly divides into, which ensures the lowest possible display latency.
            desiredFrameRate = desiredRefreshRate;
        }

        // Tell the OS about our frame rate to allow it to adapt the display refresh rate appropriately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // We want to change frame rate even if it's not seamless, since prepareDisplayForRendering()
            // will not set the display mode on S+ if it only differs by the refresh rate. It depends
            // on us to trigger the frame rate switch here.
            holder.getSurface().setFrameRate(desiredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS);
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.getSurface().setFrameRate(desiredFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (!surfaceCreated) {
            throw new IllegalStateException("Surface destroyed before creation!");
        }

        if (attemptedConnection) {
            // Let the decoder know immediately that the surface is gone
            decoderRenderer.prepareForStop();

            if (connected) {
                stopConnection();
            }
        }
    }

    @Override
    public void mouseMove(int deltaX, int deltaY) {
        conn.sendMouseMove((short) deltaX, (short) deltaY);
    }

    @Override
    public void mouseButtonEvent(int buttonId, boolean down) {
        byte buttonIndex;

        switch (buttonId)
        {
        case EvdevListener.BUTTON_LEFT:
            buttonIndex = MouseButtonPacket.BUTTON_LEFT;
            break;
        case EvdevListener.BUTTON_MIDDLE:
            buttonIndex = MouseButtonPacket.BUTTON_MIDDLE;
            break;
        case EvdevListener.BUTTON_RIGHT:
            buttonIndex = MouseButtonPacket.BUTTON_RIGHT;
            break;
        case EvdevListener.BUTTON_X1:
            buttonIndex = MouseButtonPacket.BUTTON_X1;
            break;
        case EvdevListener.BUTTON_X2:
            buttonIndex = MouseButtonPacket.BUTTON_X2;
            break;
        default:
            LimeLog.warning("Unhandled button: "+buttonId);
            return;
        }

        if (down) {
            conn.sendMouseButtonDown(buttonIndex);
        }
        else {
            conn.sendMouseButtonUp(buttonIndex);
        }
    }

    @Override
    public void mouseVScroll(byte amount) {
        conn.sendMouseScroll(amount);
    }

    @Override
    public void mouseHScroll(byte amount) {
        conn.sendMouseHScroll(amount);
    }

    @Override
    public void keyboardEvent(boolean buttonDown, short keyCode) {
        short keyMap = keyboardTranslator.translate(keyCode, -1);
        if (keyMap != 0) {
            // handleSpecialKeys() takes the Android keycode
            if (handleSpecialKeys(keyCode, buttonDown)) {
                return;
            }

            if (buttonDown) {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_DOWN, getModifierState(), (byte)0);
            }
            else {
                conn.sendKeyboardInput(keyMap, KeyboardPacket.KEY_UP, getModifierState(), (byte)0);
            }
        }
    }

    @Override
    public void onSystemUiVisibilityChange(int visibility) {
        // Don't do anything if we're not connected
        if (!connected) {
            return;
        }

        // This flag is set for all devices
        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
            hideSystemUi(2000);
        }
        else if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
            hideSystemUi(2000);
        }
    }

    @Override
    public void onPerfUpdateV(final PerformanceInfo performanceInfo) {
        long currentRxBytes = TrafficStats.getTotalRxBytes();
        long timeMillis = System.currentTimeMillis();
        long timeMillisInterval = timeMillis - previousTimeMillis;

        // 计算并更新带宽信息
        performanceInfo.bandWidth = NetHelper.calculateBandwidth(currentRxBytes, previousRxBytes, timeMillisInterval);
        previousTimeMillis = timeMillis;
        previousRxBytes = currentRxBytes;

        // 准备性能信息显示
        String resInfo = String.format("🎬 %dx%d@%.0f", 
            performanceInfo.initialWidth, performanceInfo.initialHeight, performanceInfo.totalFps);
            
        String decoderInfo = performanceInfo.decoder.replaceFirst(".*\\.(avc|hevc|av1).*", "$1").toUpperCase();
        decoderInfo += prefConfig.enableHdr ? " HDR" : "";
        
        String renderFpsInfo = String.format("Rx %.0f / Rd %.0f FPS", 
            performanceInfo.receivedFps, performanceInfo.renderedFps);

        String packetLossInfo = String.format("📶 %.2f%%", performanceInfo.lostFrameRate);

        String networkLatencyInfo = String.format("%s   %d ± %d ms", 
            performanceInfo.bandWidth, 
            (int) (performanceInfo.rttInfo >> 32), 
            (int) performanceInfo.rttInfo);
            
        String decodeLatencyInfo = String.format(performanceInfo.decodeTimeMs < 15 ? 
            "⏱️ %.2f ms" : "🥵 %.2f ms", performanceInfo.decodeTimeMs);
            
        String hostLatencyInfo = performanceInfo.framesWithHostProcessingLatency > 0 ?
            String.format("🖥 %.1f ms", performanceInfo.aveHostProcessingLatency) : "🧋 Ver.V+";

        String finalDecoderInfo = decoderInfo;
        runOnUiThread(() -> {
            // 只更新可见的性能指标
            if (perfResView != null && perfResView.getVisibility() == View.VISIBLE) {
                perfResView.setText(resInfo);
            }
            if (perfDecoderView != null && perfDecoderView.getVisibility() == View.VISIBLE) {
                perfDecoderView.setText(finalDecoderInfo);
            }
            if (perfRenderFpsView != null && perfRenderFpsView.getVisibility() == View.VISIBLE) {
                perfRenderFpsView.setText(renderFpsInfo);
            }
            if (packetLossView != null && packetLossView.getVisibility() == View.VISIBLE) {
                packetLossView.setText(packetLossInfo);
                // 根据丢包率设置颜色：小于5%为绿色，否则为红色
                packetLossView.setTextColor(performanceInfo.lostFrameRate < 5.0f ? 0xFF7D9D7D : 0xFFB57D7D);
            }
            if (networkLatencyView != null && networkLatencyView.getVisibility() == View.VISIBLE) {
                // 当丢包率不显示时，在网络延迟前添加信号图标
                boolean showPacketLoss = packetLossView != null && packetLossView.getVisibility() == View.VISIBLE;
                String displayText = showPacketLoss ? networkLatencyInfo : "🌐 " + networkLatencyInfo;
                networkLatencyView.setText(displayText);
            }
            if (decodeLatencyView != null && decodeLatencyView.getVisibility() == View.VISIBLE) {
                decodeLatencyView.setText(decodeLatencyInfo);
            }
            if (hostLatencyView != null && hostLatencyView.getVisibility() == View.VISIBLE) {
                hostLatencyView.setText(hostLatencyInfo);
            }
        });
    }

    @Override
    public boolean isPerfOverlayVisible() {
        return requestedPerformanceOverlayVisibility == View.VISIBLE;
    }

    @Override
    public void onPerfUpdateWG(final PerformanceInfo performanceInfo) {
        runOnUiThread(() -> {
            long currentRxBytes = TrafficStats.getTotalRxBytes();
            long timeMillis = System.currentTimeMillis();
            long timeMillisInterval = timeMillis - previousTimeMillis;
            if (timeMillisInterval < 3000) {
                performanceInfo.bandWidth = NetHelper.calculateBandwidth(currentRxBytes, previousRxBytes, timeMillisInterval);
            }
            previousTimeMillis = timeMillis;
            previousRxBytes = currentRxBytes;

            if (controllerManager != null && !performanceInfoDisplays.isEmpty()) {
                Map<String, String> perfAttrs = new HashMap<>();
                perfAttrs.put("decoder", performanceInfo.decoder);
                perfAttrs.put("resolution", performanceInfo.initialWidth + "x" + performanceInfo.initialHeight);
                perfAttrs.put("fps", String.format("%.0f", performanceInfo.totalFps));
                perfAttrs.put("lost_frame", String.format("%.1f", performanceInfo.lostFrameRate));
                perfAttrs.put("net_latency", String.format("%d", (int) (performanceInfo.rttInfo >> 32)));
                perfAttrs.put("host_latency", String.format("%.1f", performanceInfo.aveHostProcessingLatency));
                perfAttrs.put("decode_time", String.format("%.1f", performanceInfo.decodeTimeMs));
                perfAttrs.put("band_width", performanceInfo.bandWidth);
                for (PerformanceInfoDisplay performanceInfoDisplay : performanceInfoDisplays) {
                    performanceInfoDisplay.display(perfAttrs);
                }
            }

        });
    }

    @Override
    public void onUsbPermissionPromptStarting() {
        // Disable PiP auto-enter while the USB permission prompt is on-screen. This prevents
        // us from entering PiP while the user is interacting with the OS permission dialog.
        suppressPipRefCount++;
        updatePipAutoEnter();
    }

    @Override
    public void onUsbPermissionPromptCompleted() {
        suppressPipRefCount--;
        updatePipAutoEnter();
    }

    @Override
    public void showGameMenu(GameInputDevice device) {
        if (controllerManager != null){
            controllerManager.getSuperPagesController().returnOperation();
        } else {
            new GameMenu(this, app, conn, device);
        }

    }

    @Override
    public boolean onKey(View view, int keyCode, KeyEvent keyEvent) {
        switch (keyEvent.getAction()) {
            case KeyEvent.ACTION_DOWN:
                return handleKeyDown(keyEvent);
            case KeyEvent.ACTION_UP:
                return handleKeyUp(keyEvent);
            case KeyEvent.ACTION_MULTIPLE:
                return handleKeyMultiple(keyEvent);
            default:
                return false;
        }
    }

    public void disconnect() {
        finish();
    }

    @Override
    public void onBackPressed() {
        // Instead of "closing" the game activity open the game menu. The user has to select
        // "Disconnect" within the game menu to actually disconnect from the remote host.
        //
        // Use the onBackPressed instead of the onKey function, since the onKey function
        // also captures events while having the on-screen keyboard open.  Using onBackPressed
        // ensures that Android properly handles the back key when needed and only open the game
        // menu when the activity would be closed.
        showGameMenu(null);
    }

    private boolean isPhysicalKeyboardConnected() {
        return getResources().getConfiguration().keyboard == Configuration.KEYBOARD_QWERTY;
    }

    public void togglePerformanceOverlay() {
        if (requestedPerformanceOverlayVisibility == View.VISIBLE) {
            requestedPerformanceOverlayVisibility = View.GONE;
        } else {
            requestedPerformanceOverlayVisibility = View.VISIBLE;
            // 重新配置性能覆盖层的方向、位置和显示项
            configurePerformanceOverlay();
        }
        performanceOverlayView.setVisibility(requestedPerformanceOverlayVisibility);
    }
    
    /**
     * 刷新性能覆盖层显示项配置（用户更改配置后调用）
     */
    public void refreshPerformanceOverlayConfig() {
        if (performanceOverlayView != null && requestedPerformanceOverlayVisibility == View.VISIBLE) {
            configureDisplayItems();
            configureTextAlignment();
        }
    }

    private static byte getModifier(short key) {
        switch (key) {
            case KeyboardTranslator.VK_LSHIFT:
                return KeyboardPacket.MODIFIER_SHIFT;
            case KeyboardTranslator.VK_LCONTROL:
                return KeyboardPacket.MODIFIER_CTRL;
            case KeyboardTranslator.VK_LWIN:
                return KeyboardPacket.MODIFIER_META;
            case KeyboardTranslator.VK_MENU:
                return KeyboardPacket.MODIFIER_ALT;

            default:
                return 0;
        }
    }

    private void sendKeys(short[] keys) {
        final byte[] modifier = {(byte) 0};

        for (short key : keys) {
            conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier[0], (byte) 0);

            // Apply the modifier of the pressed key, e.g. CTRL first issues a CTRL event (without
            // modifier) and then sends the following keys with the CTRL modifier applied
            modifier[0] |= getModifier(key);
        }

        new Handler().postDelayed((() -> {

            for (int pos = keys.length - 1; pos >= 0; pos--) {
                short key = keys[pos];

                // Remove the keys modifier before releasing the key
                modifier[0] &= ~getModifier(key);

                conn.sendKeyboardInput(key, KeyboardPacket.KEY_UP, modifier[0], (byte) 0);
            }
        }), 25);
    }

    public ControllerHandler getControllerHandler() {
        return controllerHandler;
    }

    public void addPerformanceInfoDisplay(PerformanceInfoDisplay performanceInfoDisplay){
        performanceInfoDisplays.add(performanceInfoDisplay);
    }

    private void setupDisplayPosition() {
        // 获取当前偏好设置
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(this);
        
        // 获取视图容器
        ViewGroup.LayoutParams layoutParams = streamView.getLayoutParams();
        if (layoutParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) layoutParams;
            
            // 根据屏幕位置设置重力属性
            switch (config.screenPosition) {
                case TOP_LEFT:
                    params.gravity = Gravity.TOP | Gravity.LEFT;
                    break;
                case TOP_CENTER:
                    params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    break;
                case TOP_RIGHT:
                    params.gravity = Gravity.TOP | Gravity.RIGHT;
                    break;
                case CENTER_LEFT:
                    params.gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
                    break;
                case CENTER:
                    params.gravity = Gravity.CENTER;
                    break;
                case CENTER_RIGHT:
                    params.gravity = Gravity.CENTER_VERTICAL | Gravity.RIGHT;
                    break;
                case BOTTOM_LEFT:
                    params.gravity = Gravity.BOTTOM | Gravity.LEFT;
                    break;
                case BOTTOM_CENTER:
                    params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    break;
                case BOTTOM_RIGHT:
                    params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                    break;
            }
            
            // 计算偏移量的像素值
            DisplayMetrics metrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
            
            int streamWidth = prefConfig.width;
            int streamHeight = prefConfig.height;
            
            // 将0-100的偏移百分比转换为实际像素值
            int xOffset = (streamWidth * config.screenOffsetX) / 100;
            int yOffset = (streamHeight * config.screenOffsetY) / 100;
            
            // 应用偏移量
            if (params.gravity == Gravity.TOP || 
                params.gravity == (Gravity.TOP | Gravity.CENTER_HORIZONTAL) || 
                params.gravity == (Gravity.TOP | Gravity.RIGHT)) {
                params.topMargin = yOffset;
            } else if (params.gravity == Gravity.BOTTOM || 
                      params.gravity == (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) || 
                      params.gravity == (Gravity.BOTTOM | Gravity.LEFT)) {
                params.bottomMargin = yOffset;
            }
            
            if (params.gravity == Gravity.LEFT || 
                params.gravity == (Gravity.CENTER_VERTICAL | Gravity.LEFT) || 
                params.gravity == (Gravity.BOTTOM | Gravity.LEFT)) {
                params.leftMargin = xOffset;
            } else if (params.gravity == Gravity.RIGHT || 
                      params.gravity == (Gravity.CENTER_VERTICAL | Gravity.RIGHT) || 
                      params.gravity == (Gravity.TOP | Gravity.RIGHT)) {
                params.rightMargin = xOffset;
            }
            
            // 应用更新后的布局参数
            streamView.setLayoutParams(params);
        }
    }

    // 更新刷新显示位置方法
    public void refreshDisplayPosition() {
        if (surfaceCreated) {
            setupDisplayPosition();
        }
    }

    public StreamView getStreamView() {
        return streamView;
    }

    public boolean getHandleMotionEvent(StreamView streamView,MotionEvent event) {
        return handleMotionEvent(streamView,event);
    }
    
    private void configurePerformanceOverlay() {
        if (performanceOverlayView == null) {
            return;
        }
        
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) performanceOverlayView.getLayoutParams();
        
        // 设置方向
        if (prefConfig.perfOverlayOrientation == PreferenceConfiguration.PerfOverlayOrientation.VERTICAL) {
            performanceOverlayView.setOrientation(LinearLayout.VERTICAL);
            performanceOverlayView.setBackgroundColor(getResources().getColor(R.color.overlay_background_vertical));
        } else {
            performanceOverlayView.setOrientation(LinearLayout.HORIZONTAL);
            performanceOverlayView.setBackgroundColor(getResources().getColor(R.color.overlay_background_horizontal));
        }
        
        // 根据用户配置显示/隐藏特定的性能指标
        configureDisplayItems();
        
        // 从SharedPreferences读取保存的位置
        SharedPreferences prefs = getSharedPreferences("performance_overlay", MODE_PRIVATE);
        boolean hasCustomPosition = prefs.getBoolean("has_custom_position", false);
        
        if (hasCustomPosition) {
            // 使用自定义位置
            layoutParams.gravity = Gravity.NO_GRAVITY;
            layoutParams.leftMargin = prefs.getInt("left_margin", 0);
            layoutParams.topMargin = prefs.getInt("top_margin", 0);
            layoutParams.rightMargin = 0;
            layoutParams.bottomMargin = 0;
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
            layoutParams.rightMargin = 0;
            layoutParams.bottomMargin = 0;
        }
        
        performanceOverlayView.setLayoutParams(layoutParams);
        
        // 根据位置和方向调整文字对齐（延迟执行确保View已测量）
        performanceOverlayView.post(new Runnable() {
            @Override
            public void run() {
                configureTextAlignment();
            }
        });
        
        // 设置拖动监听器
        setupPerformanceOverlayDragging();
    }
    
    private void configureDisplayItems() {
        // 根据用户配置显示/隐藏特定的性能指标
        if (perfResView != null) {
            perfResView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(this, "resolution") ? 
                View.VISIBLE : View.GONE);
        }
        if (perfDecoderView != null) {
            perfDecoderView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(this, "decoder") ? 
                View.VISIBLE : View.GONE);
        }
        if (perfRenderFpsView != null) {
            perfRenderFpsView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(this, "render_fps") ? 
                View.VISIBLE : View.GONE);
        }
        if (networkLatencyView != null) {
            networkLatencyView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(this, "network_latency") ? 
                View.VISIBLE : View.GONE);
        }
        if (decodeLatencyView != null) {
            decodeLatencyView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(this, "decode_latency") ? 
                View.VISIBLE : View.GONE);
        }
        if (hostLatencyView != null) {
            hostLatencyView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(this, "host_latency") ? 
                View.VISIBLE : View.GONE);
        }
        if (packetLossView != null) {
            packetLossView.setVisibility(PerfOverlayDisplayItemsPreference.isItemEnabled(this, "packet_loss") ?
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
        int gravity = (isVertical && isRightSide) ? android.view.Gravity.END : android.view.Gravity.START;
        
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
        SharedPreferences prefs = getSharedPreferences("performance_overlay", MODE_PRIVATE);
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
     * 配置单个TextView的样式（对齐方式和阴影效果）
     */
    private void configureTextViewStyle(TextView textView, int gravity, boolean isVertical) {
        // 设置文字对齐方式
        textView.setGravity(gravity);
        
        // 根据布局方向设置阴影效果
        if (isVertical) {
            // 竖屏时添加字体阴影，提高可读性
            textView.setShadowLayer(2.0f, 1.0f, 1.0f, 0x80000000);
        } else {
            // 横屏时移除阴影
            textView.setShadowLayer(0, 0, 0, 0);
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private void setupPerformanceOverlayDragging() {
        if (performanceOverlayView == null) {
            return;
        }
        
        performanceOverlayView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        isDraggingPerfOverlay = true;
                        perfOverlayStartX = event.getRawX();
                        perfOverlayStartY = event.getRawY();
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
                            
                            snapToNearestPosition(v);
                            
                            return true;
                        }
                        break;
                }
                return false;
            }
        });
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
        SharedPreferences prefs = getSharedPreferences("performance_overlay", MODE_PRIVATE);
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

    /**
     * 设置显示器监听器
     */
    private void setupDisplayListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            displayListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    LimeLog.info("Display added: " + displayId);
                    if (prefConfig.useExternalDisplay && displayId != Display.DEFAULT_DISPLAY) {
                        // 外接显示器已连接
                        checkForExternalDisplay();
                        if (useExternalDisplay) {
                            startExternalDisplayPresentation();
                        }
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    LimeLog.info("Display removed: " + displayId);
                    if (externalDisplay != null && displayId == externalDisplay.getDisplayId()) {
                        // 外接显示器已断开
                        if (externalPresentation != null) {
                            externalPresentation.dismiss();
                            externalPresentation = null;
                        }
                        externalDisplay = null;
                        useExternalDisplay = false;

                        // 显示主屏幕内容
                        findViewById(R.id.surfaceView).setVisibility(View.VISIBLE);
                        Toast.makeText(Game.this, "外接显示器已断开，切换到主屏幕", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    LimeLog.info("Display changed: " + displayId);
                }
            };

            displayManager.registerDisplayListener(displayListener, null);
        }
    }

    /**
     * 检查并配置外接显示器
     */
    private void checkForExternalDisplay() {
        // 如果用户没有启用外接显示器选项，直接返回
        if (!prefConfig.useExternalDisplay) {
            LimeLog.info("External display disabled by user preference");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Display[] displays = displayManager.getDisplays();

            // 查找外接显示器（不是主显示器）
            for (Display display : displays) {
                if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    externalDisplay = display;
                    useExternalDisplay = true;
                    LimeLog.info("Found external display: " + display.getName() +
                            " (ID: " + display.getDisplayId() + ")");
                    break;
                }
            }

            if (!useExternalDisplay) {
                LimeLog.info("No external display found, using default display");
            }
        }
    }

    /**
     * 获取要使用的显示器
     */
    private Display getTargetDisplay() {
        if (useExternalDisplay && externalDisplay != null) {
            return externalDisplay;
        }
        return getWindowManager().getDefaultDisplay();
    }

    /**
     * 检查是否有外接显示器连接
     */
    public static boolean hasExternalDisplay(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) {
                Display[] displays = displayManager.getDisplays();
                for (Display display : displays) {
                    if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 将Activity移动到外接显示器
     */
    private void moveToExternalDisplay() {
        if (useExternalDisplay && externalDisplay != null &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            // 创建WindowManager.LayoutParams for external display
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.preferredDisplayModeId = externalDisplay.getMode().getModeId();
            getWindow().setAttributes(params);

            // 或者使用Presentation来在外接显示器上显示
            // 这需要重新设计Activity结构
        }
    }

    /**
     * 外接显示器演示类
     */
    private class ExternalDisplayPresentation extends Presentation {

        public ExternalDisplayPresentation(Context outerContext, Display display) {
            super(outerContext, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // 设置全屏
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

            // 强制横屏
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

            // 设置内容视图
            setContentView(R.layout.activity_game);

            // 初始化StreamView
            StreamView externalStreamView = findViewById(R.id.surfaceView);
            if (externalStreamView != null) {
                // 替换主Activity的StreamView
                streamView = externalStreamView;
                streamView.setOnGenericMotionListener(Game.this);
                streamView.setOnKeyListener(Game.this);
                streamView.setInputCallbacks(Game.this);

                // 设置触摸监听
                View backgroundTouchView = findViewById(R.id.backgroundTouchView);
                if (backgroundTouchView != null) {
                    backgroundTouchView.setOnTouchListener(Game.this);
                }
                
                // 初始化通知覆盖层
                notificationOverlayView = findViewById(R.id.notificationOverlay);

                // 设置Surface回调
                streamView.getHolder().addCallback(Game.this);
            }
        }

        @Override
        public void onDisplayRemoved() {
            super.onDisplayRemoved();
            // 外接显示器被移除时，关闭串流
            Game.this.finish();
        }
    }

    private ExternalDisplayPresentation externalPresentation;

    /**
     * 启动外接显示器演示
     */
    @SuppressLint({"ResourceAsColor", "SetTextI18n"})
    private void startExternalDisplayPresentation() {
        if (!(useExternalDisplay && externalDisplay != null && externalPresentation == null)) {
            return;
        }

        externalPresentation = new ExternalDisplayPresentation(this, externalDisplay);
        externalPresentation.show();

        // 隐藏主Activity的内容
        View surfaceView = findViewById(R.id.surfaceView);
        if (surfaceView != null) {
            surfaceView.setVisibility(View.GONE);
        }

        if (prefConfig.enablePerfOverlay) {
            // 创建电量显示TextView
            final TextView batteryTextView = new TextView(this);
            batteryTextView.setGravity(Gravity.CENTER);
            batteryTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
            batteryTextView.setTextColor(getResources().getColor(R.color.scene_color_1));

            // 设置布局参数（居中显示）
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.CENTER;
            batteryTextView.setLayoutParams(params);

            // 添加到内建屏幕（主Activity）视图的中间
            FrameLayout rootView = findViewById(android.R.id.content);
            if (rootView != null) {
                rootView.addView(batteryTextView);
            }

            // 创建定时更新任务
            final Handler handler = new Handler();
            final Runnable updateBatteryTask = new Runnable() {
                @Override
                public void run() {
                    batteryTextView.setText(String.format("🔋 %d%%", UiHelper.getBatteryLevel(Game.this)));
                    handler.postDelayed(this, 60000); // 每分钟更新一次
                }
            };

            // 立即执行首次更新并启动定时器
            updateBatteryTask.run();
        }

        Toast.makeText(this, "串流已切换到外接显示器, 若某些外接设备不能正常横屏显示，请翻滚主机。", Toast.LENGTH_LONG).show();
    }
}
