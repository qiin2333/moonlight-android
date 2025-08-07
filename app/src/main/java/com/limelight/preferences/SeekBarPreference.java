package com.limelight.preferences;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

// Based on a Stack Overflow example: http://stackoverflow.com/questions/1974193/slider-on-my-preferencescreen
public class SeekBarPreference extends DialogPreference
{
    private static final String ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android";
    private static final String SEEKBAR_SCHEMA_URL = "http://schemas.moonlight-stream.com/apk/res/seekbar";
    private static final String TAG = "SeekBarPreference";

    private SeekBar seekBar;
    private TextView valueText;
    private final Context context;

    private final String dialogMessage;
    private final String suffix;
    private final int defaultValue;
    private final int maxValue;
    private final int minValue;
    private final int stepSize;
    private final int keyStepSize;
    private final int divisor;
    private int currentValue;
    
    // 对数变换参数
    private static final double LOG_BASE = 10.0;
    private boolean isLogarithmic = false;

    public SeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;

        // Read the message from XML
        int dialogMessageId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "dialogMessage", 0);
        if (dialogMessageId == 0) {
            dialogMessage = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "dialogMessage");
        }
        else {
            dialogMessage = context.getString(dialogMessageId);
        }

        // Get the suffix for the number displayed in the dialog
        int suffixId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "text", 0);
        if (suffixId == 0) {
            suffix = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "text");
        }
        else {
            suffix = context.getString(suffixId);
        }

        // Get default, min, and max seekbar values
        defaultValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue", PreferenceConfiguration.getDefaultBitrate(context));
        maxValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100);
        minValue = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1);
        stepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1);
        divisor = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1);
        keyStepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0);
        
        // 检查是否为码率设置
        String key = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "key");
        if (key != null && key.equals(PreferenceConfiguration.BITRATE_PREF_STRING)) {
            isLogarithmic = true;
        }
    }
    
    // 将线性滑块值转换为对数刻度值
    private int linearToLog(int linearValue) {
        if (linearValue <= minValue) return minValue;
        
        // 计算对数比例尺
        double minLog = Math.log10(minValue);
        double maxLog = Math.log10(maxValue);
        double normalizedValue = (linearValue - minValue) / (double)(maxValue - minValue);
        double logValue = Math.pow(LOG_BASE, minLog + normalizedValue * (maxLog - minLog));
        
        // 确保结果在范围内并按步长取整
        int result = (int) Math.round(logValue);
        result = Math.max(minValue, Math.min(maxValue, result));
        return ((result + (stepSize - 1))/stepSize)*stepSize;
    }
    
    // 将对数刻度值转换回线性滑块值
    private int logToLinear(int logValue) {
        if (logValue <= minValue) return minValue;
        
        // 计算线性比例尺
        double minLog = Math.log10(minValue);
        double maxLog = Math.log10(maxValue);
        double normalizedValue = (Math.log10(logValue) - minLog) / (maxLog - minLog);
        double linearValue = minValue + normalizedValue * (maxValue - minValue);
        
        // 确保结果在范围内
        return (int) Math.round(linearValue);
    }

    @Override
    protected View onCreateDialogView() {

        LinearLayout.LayoutParams params;
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(6, 6, 6, 6);

        TextView splashText = new TextView(context);
        splashText.setPadding(30, 10, 30, 10);
        if (dialogMessage != null) {
            splashText.setText(dialogMessage);
        }
        layout.addView(splashText);

        valueText = new TextView(context);
        valueText.setGravity(Gravity.CENTER_HORIZONTAL);
        valueText.setTextSize(32);
        // Default text for value; hides bug where OnSeekBarChangeListener isn't called when opacity is 0%
        valueText.setText("0%");
        params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.addView(valueText, params);

        seekBar = new SeekBar(context);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean b) {
                if (value < minValue) {
                    seekBar.setProgress(minValue);
                    return;
                }

                int roundedValue = ((value + (stepSize - 1))/stepSize)*stepSize;
                if (roundedValue != value) {
                    seekBar.setProgress(roundedValue);
                    return;
                }
                
                // 如果是码率设置，应用对数变换
                int displayValue = value;
                if (isLogarithmic) {
                    displayValue = linearToLog(value);
                }

                String t;
                if (divisor != 1) {
                    float floatValue = displayValue / (float)divisor;
                    t = String.format((Locale)null, "%.1f", floatValue);
                }
                else {
                    t = String.valueOf(displayValue);
                }
                valueText.setText(suffix == null ? t : t.concat(suffix.length() > 1 ? " "+suffix : suffix));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        layout.addView(seekBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (shouldPersist()) {
            currentValue = getPersistedInt(defaultValue);
        }

        seekBar.setMax(maxValue);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        
        // 如果是码率设置，将对数值转换为线性值显示
        if (isLogarithmic && currentValue > 0) {
            seekBar.setProgress(logToLinear(currentValue));
        } else {
            seekBar.setProgress(currentValue);
        }

        return layout;
    }

    @Override
    protected void onBindDialogView(View v) {
        super.onBindDialogView(v);
        seekBar.setMax(maxValue);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        
        // 如果是码率设置，将对数值转换为线性值显示
        if (isLogarithmic && currentValue > 0) {
            seekBar.setProgress(logToLinear(currentValue));
        } else {
            seekBar.setProgress(currentValue);
        }
    }

    @Override
    protected void onSetInitialValue(boolean restore, Object defaultValue)
    {
        super.onSetInitialValue(restore, defaultValue);
        if (restore) {
            currentValue = shouldPersist() ? getPersistedInt(this.defaultValue) : 0;
        }
        else {
            currentValue = (Integer) defaultValue;
        }
    }

    public void setProgress(int progress) {
        this.currentValue = progress;
        if (seekBar != null) {
            if (isLogarithmic && progress > 0) {
                seekBar.setProgress(logToLinear(progress));
            } else {
                seekBar.setProgress(progress);
            }
        }
    }
    
    public int getProgress() {
        return currentValue;
    }

    @Override
    public void showDialog(Bundle state) {
        super.showDialog(state);

        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null) {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (shouldPersist()) {
                        int valueToSave = seekBar.getProgress();
                        
                        // 如果是码率设置，保存对数变换后的值
                        if (isLogarithmic) {
                            valueToSave = linearToLog(valueToSave);
                        }
                        
                        currentValue = valueToSave;
                        persistInt(valueToSave);
                        callChangeListener(valueToSave);
                    }

                    getDialog().dismiss();
                }
            });
        }
    }
}
