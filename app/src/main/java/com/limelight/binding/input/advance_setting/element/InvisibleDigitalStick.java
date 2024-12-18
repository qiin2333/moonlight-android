package com.limelight.binding.input.advance_setting.element;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputFilter;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

import java.util.Map;

public class InvisibleDigitalStick extends Element {

    private static final String COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS = COLUMN_INT_ELEMENT_SENSE;

    /**
     * outer radius size in percent of the ui element
     */
    public static final int SIZE_RADIUS_COMPLETE = 90;
    /**
     * analog stick size in percent of the ui element
     */
    public static final int SIZE_RADIUS_ANALOG_STICK = 90;
    /**
     * dead zone size in percent of the ui element
     */
    public static final int SIZE_RADIUS_DEADZONE = 90;
    /**
     * time frame for a double click
     */
    public final static long timeoutDoubleClick = 350;

    /**
     * touch down time until the deadzone is lifted to allow precise movements with the analog sticks
     */
    public final static long timeoutDeadzone = 150;

    /**
     * Listener interface to update registered observers.
     */
    public interface InvisibleDigitalStickListener {

        /**
         * onMovement event will be fired on real analog stick movement (outside of the deadzone).
         *
         * @param x horizontal position, value from -1.0 ... 0 .. 1.0
         * @param y vertical position, value from -1.0 ... 0 .. 1.0
         */
        void onMovement(float x, float y);

        /**
         * onClick event will be fired on click on the analog stick
         */
        void onClick();

        /**
         * onDoubleClick event will be fired on a double click in a short time frame on the analog
         * stick.
         */
        void onDoubleClick();

        /**
         * onRevoke event will be fired on unpress of the analog stick.
         */
        void onRevoke();
    }

    /**
     * Movement states of the analog sick.
     */
    private enum STICK_STATE {
        NO_MOVEMENT,
        MOVED_IN_DEAD_ZONE,
        MOVED_ACTIVE
    }

    /**
     * Click type states.
     */
    private enum CLICK_STATE {
        SINGLE,
        DOUBLE
    }

    /**
     * configuration if the analog stick should be displayed as circle or square
     */
    private boolean circle_stick = true; // TODO: implement square sick for simulations

    /**
     * outer radius, this size will be automatically updated on resize
     */
    private float radius_complete = 0;
    /**
     * analog stick radius, this size will be automatically updated on resize
     */
    private float radius_analog_stick = 0;
    /**
     * dead zone radius, this size will be automatically updated on resize
     */
    private float radius_dead_zone = 0;

    /**
     * horizontal position in relation to the center of the element
     */
    private float relative_x = 0;
    /**
     * vertical position in relation to the center of the element
     */
    private float relative_y = 0;


    private double movement_radius = 0;
    private double movement_angle = 0;

    private float position_stick_x = 0;
    private float position_stick_y = 0;
    private float circleCenterX = 0;
    private float circleCenterY = 0;

    private SuperConfigDatabaseHelper superConfigDatabaseHelper;
    private PageDeviceController pageDeviceController;
    private InvisibleDigitalStick invisibleDigitalStick;

    private ElementController.SendEventHandler middleValueSendHandler;
    private ElementController.SendEventHandler upValueSendHandler;
    private ElementController.SendEventHandler downValueSendHandler;
    private ElementController.SendEventHandler leftValueSendHandler;
    private ElementController.SendEventHandler rightValueSendHandler;
    private String middleValue;
    private String upValue;
    private String downValue;
    private String leftValue;
    private String rightValue;
    private int radius;
    private int deadZoneRadius; //dead zone radius
    private int thick;
    private int normalColor;
    private int pressedColor;
    private int backgroundColor;

    private SuperPageLayout invisibleDigitalStickPage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;

    private final Paint paintStick = new Paint();
    private final Paint paintBackground = new Paint();
    private final Paint paintEdit = new Paint();
    private final RectF rect = new RectF();

    private InvisibleDigitalStick.STICK_STATE stick_state = InvisibleDigitalStick.STICK_STATE.NO_MOVEMENT;
    private InvisibleDigitalStick.CLICK_STATE click_state = InvisibleDigitalStick.CLICK_STATE.SINGLE;

    private InvisibleDigitalStickListener listener;
    private long timeLastClick = 0;
    private boolean upIsPressed = false;
    private boolean downIsPressed = false;
    private boolean leftIsPressed = false;
    private boolean rightIsPressed = false;

    private static double getMovementRadius(float x, float y) {
        return Math.sqrt(x * x + y * y);
    }

    private static double getAngle(float way_x, float way_y) {
        // prevent divisions by zero for corner cases
        if (way_x == 0) {
            return way_y < 0 ? 0 : Math.PI;
        } else if (way_y == 0) {
            if (way_x > 0) {
                return Math.PI * 3 / 2;
            } else if (way_x < 0) {
                return Math.PI * 1 / 2;
            }
        }
        // return correct calculated angle for each quadrant
        if (way_x > 0) {
            if (way_y < 0) {
                // first quadrant
                return 3 * Math.PI / 2 + Math.atan((double) (-way_y / way_x));
            } else {
                // second quadrant
                return Math.PI + Math.atan((double) (way_x / way_y));
            }
        } else {
            if (way_y > 0) {
                // third quadrant
                return Math.PI / 2 + Math.atan((double) (way_y / -way_x));
            } else {
                // fourth quadrant
                return 0 + Math.atan((double) (-way_x / -way_y));
            }
        }
    }

    public InvisibleDigitalStick(Map<String,Object> attributesMap,
                                 ElementController controller,
                                 PageDeviceController pageDeviceController, Context context) {
        super(attributesMap,controller,context);
        // reset stick position
        circleCenterX = getWidth() / 2;
        circleCenterY = getHeight() / 2;
        position_stick_x = circleCenterX;
        position_stick_y = circleCenterY;


        this.pageDeviceController = pageDeviceController;
        this.invisibleDigitalStick = this;


        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Game)context).getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        super.centralXMax  = displayMetrics.widthPixels;
        super.centralXMin  = 0;
        super.centralYMax  = displayMetrics.heightPixels;
        super.centralYMin  = 0;
        super.widthMax  = displayMetrics.widthPixels;
        super.widthMin  = 100;
        super.heightMax  = displayMetrics.heightPixels;
        super.heightMin  = 100;

        paintBackground.setStyle(Paint.Style.FILL);
        paintStick.setStyle(Paint.Style.STROKE);
        paintEdit.setStyle(Paint.Style.STROKE);
        paintEdit.setStrokeWidth(4);
        paintEdit.setPathEffect(new DashPathEffect(new float[]{10, 20}, 0));

        radius = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_RADIUS)).intValue();
        deadZoneRadius = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS)).intValue();
        thick = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_THICK)).intValue();
        normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
        pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
        backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
        middleValue = (String) attributesMap.get(COLUMN_STRING_ELEMENT_MIDDLE_VALUE);
        upValue = (String) attributesMap.get(COLUMN_STRING_ELEMENT_UP_VALUE);
        downValue = (String) attributesMap.get(COLUMN_STRING_ELEMENT_DOWN_VALUE);
        leftValue = (String) attributesMap.get(COLUMN_STRING_ELEMENT_LEFT_VALUE);
        rightValue = (String) attributesMap.get(COLUMN_STRING_ELEMENT_RIGHT_VALUE);
        middleValueSendHandler = controller.getSendEventHandler(middleValue);
        upValueSendHandler = controller.getSendEventHandler(upValue);
        downValueSendHandler = controller.getSendEventHandler(downValue);
        leftValueSendHandler = controller.getSendEventHandler(leftValue);
        rightValueSendHandler = controller.getSendEventHandler(rightValue);

        listener = new InvisibleDigitalStickListener() {
            @Override
            public void onMovement(float x, float y) {
                if (x < -deadZoneRadius *0.01 && !leftIsPressed) {
                    leftValueSendHandler.sendEvent(true);
                    leftIsPressed = true;
                } else if (x > -deadZoneRadius *0.01 && leftIsPressed) {
                    leftValueSendHandler.sendEvent(false);
                    leftIsPressed = false;
                }
                if (x > deadZoneRadius *0.01 && !rightIsPressed) {
                    rightValueSendHandler.sendEvent(true);
                    rightIsPressed = true;
                } else if (x < deadZoneRadius *0.01 && rightIsPressed) {
                    rightValueSendHandler.sendEvent(false);
                    rightIsPressed = false;
                }
                if (y < -deadZoneRadius *0.01 && !downIsPressed) {
                    downValueSendHandler.sendEvent(true);
                    downIsPressed = true;
                } else if (y > -deadZoneRadius *0.01 && downIsPressed) {
                    downValueSendHandler.sendEvent(false);
                    downIsPressed = false;
                }
                if (y > deadZoneRadius *0.01 && !upIsPressed) {
                    upValueSendHandler.sendEvent(true);
                    upIsPressed = true;
                } else if (y < deadZoneRadius *0.01 && upIsPressed) {
                    upValueSendHandler.sendEvent(false);
                    upIsPressed = false;
                }
            }

            @Override
            public void onClick() {
                elementController.buttonVibrator();
            }

            @Override
            public void onDoubleClick() {
                middleValueSendHandler.sendEvent(true);
            }

            @Override
            public void onRevoke() {
                middleValueSendHandler.sendEvent(false);
            }
        };

        radius_complete = getPercent(radius, 100) - 2 * thick;
        radius_dead_zone = getPercent(radius, deadZoneRadius);
        radius_analog_stick = getPercent(radius, 20);
    }

    private void notifyOnMovement(float x, float y) {
        // notify listeners
        listener.onMovement(x, y);
    }

    private void notifyOnClick() {
        // notify listeners
        listener.onClick();
    }

    private void notifyOnDoubleClick() {
        // notify listeners
        listener.onDoubleClick();
    }

    private void notifyOnRevoke() {
        // notify listeners
        listener.onRevoke();
    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (invisibleDigitalStickPage == null){
            invisibleDigitalStickPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_invisible_digital_stick,null);
            centralXNumberSeekbar = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_central_x);
            centralYNumberSeekbar = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_central_y);

        }

        NumberSeekbar widthNumberSeekbar = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_width);
        NumberSeekbar heightNumberSeekbar = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_height);
        NumberSeekbar radiusNumberSeekbar = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_radius);
        TextView middleValueTextView = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_middle_value);
        TextView upValueTextView = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_up_value);
        TextView downValueTextView = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_down_value);
        TextView leftValueTextView = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_left_value);
        TextView rightValueTextView = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_right_value);
        NumberSeekbar senseNumberSeekbar = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_sense);
        NumberSeekbar thickNumberSeekbar = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_thick);
        NumberSeekbar layerNumberSeekbar = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_layer);
        ElementEditText normalColorEditText = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_normal_color);
        ElementEditText pressedColorEditText = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_pressed_color);
        ElementEditText backgroundColorEditText = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_background_color);
        Button copyButton = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_copy);
        Button deleteButton = invisibleDigitalStickPage.findViewById(R.id.page_invisible_digital_stick_delete);


        middleValueTextView.setText(pageDeviceController.getKeyNameByValue(middleValue));
        middleValueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        setElementMiddleValue(key.getTag().toString());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack,View.VISIBLE,View.VISIBLE,View.VISIBLE);
            }
        });
        upValueTextView.setText(pageDeviceController.getKeyNameByValue(upValue));
        upValueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        setElementUpValue(key.getTag().toString());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack,View.VISIBLE,View.VISIBLE,View.VISIBLE);
            }
        });
        downValueTextView.setText(pageDeviceController.getKeyNameByValue(downValue));
        downValueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        setElementDownValue(key.getTag().toString());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack,View.VISIBLE,View.VISIBLE,View.VISIBLE);
            }
        });
        leftValueTextView.setText(pageDeviceController.getKeyNameByValue(leftValue));
        leftValueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        setElementLeftValue(key.getTag().toString());
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack,View.VISIBLE,View.VISIBLE,View.VISIBLE);
            }
        });
        rightValueTextView.setText(pageDeviceController.getKeyNameByValue(rightValue));
        rightValueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        setElementRightValue(key.getTag().toString());
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        save();
                    }
                };
                pageDeviceController.open(deviceCallBack,View.VISIBLE,View.VISIBLE,View.VISIBLE);
            }
        });

        centralXNumberSeekbar.setProgressMin(centralXMin);
        centralXNumberSeekbar.setProgressMax(centralXMax);
        centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
        centralXNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementCentralX(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });

        centralYNumberSeekbar.setProgressMin(centralYMin);
        centralYNumberSeekbar.setProgressMax(centralYMax);
        centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        centralYNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementCentralY(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });


        widthNumberSeekbar.setProgressMax(widthMax);
        widthNumberSeekbar.setProgressMin(widthMin);
        widthNumberSeekbar.setValueWithNoCallBack(getElementWidth());
        widthNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementWidth(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
                save();

            }
        });

        heightNumberSeekbar.setProgressMax(heightMax);
        heightNumberSeekbar.setProgressMin(heightMin);
        heightNumberSeekbar.setValueWithNoCallBack(getElementHeight());
        heightNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementHeight(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
                save();
            }
        });


        senseNumberSeekbar.setValueWithNoCallBack(deadZoneRadius);
        senseNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementDeadZoneRadius(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });





        radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
        radiusNumberSeekbar.setProgressMin(10);
        radiusNumberSeekbar.setValueWithNoCallBack(radius);
        radiusNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementRadius(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });


        thickNumberSeekbar.setValueWithNoCallBack(thick);
        thickNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementThick(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });


        layerNumberSeekbar.setValueWithNoCallBack(layer);
        layerNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setElementLayer(seekBar.getProgress());
                save();
            }
        });

        normalColorEditText.setTextWithNoTextChangedCallBack(String.format("%08X",normalColor));
        normalColorEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new HexInputFilter()});
        normalColorEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    setElementNormalColor((int) Long.parseLong(text, 16));
                    save();
                }
            }
        });


        pressedColorEditText.setTextWithNoTextChangedCallBack(String.format("%08X",pressedColor));
        pressedColorEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new HexInputFilter()});
        pressedColorEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    setElementPressedColor((int) Long.parseLong(text, 16));
                    save();
                }
            }
        });


        backgroundColorEditText.setTextWithNoTextChangedCallBack(String.format("%08X",backgroundColor));
        backgroundColorEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new HexInputFilter()});
        backgroundColorEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    setElementBackgroundColor((int) Long.parseLong(text, 16));
                    save();
                }
            }
        });


        copyButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_TYPE,ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK);
                contentValues.put(COLUMN_STRING_ELEMENT_UP_VALUE,upValue);
                contentValues.put(COLUMN_STRING_ELEMENT_DOWN_VALUE,downValue);
                contentValues.put(COLUMN_STRING_ELEMENT_LEFT_VALUE,leftValue);
                contentValues.put(COLUMN_STRING_ELEMENT_RIGHT_VALUE,rightValue);
                contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE,middleValue);
                contentValues.put(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS, deadZoneRadius);
                contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth());
                contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight());
                contentValues.put(COLUMN_INT_ELEMENT_LAYER,layer);
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,Math.max(Math.min(getElementCentralX() + getElementWidth(),centralXMax),centralXMin));
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
                contentValues.put(COLUMN_INT_ELEMENT_RADIUS,radius);
                contentValues.put(COLUMN_INT_ELEMENT_THICK,thick);
                contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,normalColor);
                contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,pressedColor);
                contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,backgroundColor);
                elementController.addElement(contentValues);
            }
        });

        deleteButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                elementController.toggleInfoPage(invisibleDigitalStickPage);
                elementController.deleteElement(invisibleDigitalStick);
            }
        });



        return invisibleDigitalStickPage;
    }

    @Override
    public void save() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_STRING_ELEMENT_UP_VALUE,upValue);
        contentValues.put(COLUMN_STRING_ELEMENT_DOWN_VALUE,downValue);
        contentValues.put(COLUMN_STRING_ELEMENT_LEFT_VALUE,leftValue);
        contentValues.put(COLUMN_STRING_ELEMENT_RIGHT_VALUE,rightValue);
        contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE,middleValue);
        contentValues.put(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS, deadZoneRadius);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth());
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,getElementCentralX());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS,radius);
        contentValues.put(COLUMN_INT_ELEMENT_THICK,thick);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER,layer);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,normalColor);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,pressedColor);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,backgroundColor);
        elementController.updateElement(elementId,contentValues);

    }

    @Override
    protected void updatePage() {
        if (invisibleDigitalStickPage != null){
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }

    }

    @Override
    protected void onElementDraw(Canvas canvas) {
        // set transparent background
        paintBackground.setColor(backgroundColor);
        rect.top = 0;
        rect.left = 0;
        rect.right = getWidth();
        rect.bottom = getHeight();
        canvas.drawRect(rect,paintBackground);

        ElementController.Mode mode = elementController.getMode();
        if (mode == ElementController.Mode.Edit || mode == ElementController.Mode.Select){
            // 绘画范围
            rect.left = rect.top = 2;
            rect.right = getWidth() - 2;
            rect.bottom = getHeight() - 2;
            // 边框
            paintEdit.setColor(editColor);
            canvas.drawRect(rect,paintEdit);


            paintStick.setStrokeWidth(thick);
            // draw outer circle
            if (!isPressed() || click_state == InvisibleDigitalStick.CLICK_STATE.SINGLE) {
                paintStick.setColor(normalColor);
            } else {
                paintStick.setColor(pressedColor);
            }
            canvas.drawCircle(getWidth() / 2, getHeight() / 2 , radius_complete, paintStick);

            paintStick.setColor(normalColor);
            // draw dead zone
            canvas.drawCircle(getWidth() / 2, getHeight() / 2, radius_dead_zone, paintStick);

            paintStick.setColor(normalColor);
            canvas.drawCircle(getWidth() / 2, getHeight() / 2, radius_analog_stick, paintStick);
        }

        if (!isPressed()){
            return;
        }


        paintStick.setStyle(Paint.Style.STROKE);
        paintStick.setStrokeWidth(thick);
        // draw outer circle
        if (!isPressed() || click_state == InvisibleDigitalStick.CLICK_STATE.SINGLE) {
            paintStick.setColor(normalColor);
        } else {
            paintStick.setColor(pressedColor);
        }
        canvas.drawCircle(circleCenterX, circleCenterY, radius_complete, paintStick);

        paintStick.setColor(normalColor);
        // draw dead zone
        canvas.drawCircle(circleCenterX, circleCenterY, radius_dead_zone, paintStick);

        // draw stick depending on state
        switch (stick_state) {
            case NO_MOVEMENT: {
                paintStick.setColor(normalColor);
                canvas.drawCircle(circleCenterX, circleCenterY, radius_analog_stick, paintStick);
                break;
            }
            case MOVED_IN_DEAD_ZONE:
            case MOVED_ACTIVE: {
                paintStick.setColor(pressedColor);
                canvas.drawCircle(position_stick_x, position_stick_y, radius_analog_stick, paintStick);
                break;
            }
        }
    }

    private void updatePosition(long eventTime) {
        // get 100% way
        float complete = radius_complete - radius_analog_stick;

        // calculate relative way
        float correlated_y = (float) (Math.sin(Math.PI / 2 - movement_angle) * (movement_radius));
        float correlated_x = (float) (Math.cos(Math.PI / 2 - movement_angle) * (movement_radius));

        // update positions
        position_stick_x = circleCenterX - correlated_x;
        position_stick_y = circleCenterY - correlated_y;

        // Stay active even if we're back in the deadzone because we know the user is actively
        // giving analog stick input and we don't want to snap back into the deadzone.
        // We also release the deadzone if the user keeps the stick pressed for a bit to allow
        // them to make precise movements.
        stick_state = (stick_state == InvisibleDigitalStick.STICK_STATE.MOVED_ACTIVE ||
                eventTime - timeLastClick > timeoutDeadzone ||
                movement_radius > radius_dead_zone) ?
                InvisibleDigitalStick.STICK_STATE.MOVED_ACTIVE : InvisibleDigitalStick.STICK_STATE.MOVED_IN_DEAD_ZONE;

        //  trigger move event if state active
        if (stick_state == InvisibleDigitalStick.STICK_STATE.MOVED_ACTIVE) {
            notifyOnMovement(-correlated_x / complete, correlated_y / complete);
        }
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN){
            //点击后扩大view，防止摇杆显示不全
            circleCenterX = event.getX();
            circleCenterY = event.getY();
            invalidate();
        }
        // save last click state
        InvisibleDigitalStick.CLICK_STATE lastClickState = click_state;

        // get absolute way for each axis
        relative_x = -(circleCenterX - event.getX());
        relative_y = -(circleCenterY - event.getY());

        // get radius and angel of movement from center
        movement_radius = getMovementRadius(relative_x, relative_y);
        movement_angle = getAngle(relative_x, relative_y);

        // pass touch event to parent if out of outer circle
        if (movement_radius > radius_complete && !isPressed())
            return false;

        // chop radius if out of outer circle or near the edge
        if (movement_radius > (radius_complete - radius_analog_stick)) {
            movement_radius = radius_complete - radius_analog_stick;
        }

        // handle event depending on action
        switch (event.getActionMasked()) {
            // down event (touch event)
            case MotionEvent.ACTION_DOWN: {

                // set to dead zoned, will be corrected in update position if necessary
                stick_state = InvisibleDigitalStick.STICK_STATE.MOVED_IN_DEAD_ZONE;
                // check for double click
                if (lastClickState == InvisibleDigitalStick.CLICK_STATE.SINGLE &&
                        event.getEventTime() - timeLastClick <= timeoutDoubleClick) {
                    click_state = InvisibleDigitalStick.CLICK_STATE.DOUBLE;
                    notifyOnDoubleClick();
                } else {
                    click_state = InvisibleDigitalStick.CLICK_STATE.SINGLE;
                    notifyOnClick();
                }
                // reset last click timestamp
                timeLastClick = event.getEventTime();
                // set item pressed and update
                setPressed(true);
                break;
            }
            // up event (revoke touch)
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                setPressed(false);
                break;
            }
        }

        if (isPressed()) {
            // when is pressed calculate new positions (will trigger movement if necessary)
            updatePosition(event.getEventTime());
        } else {
            stick_state = InvisibleDigitalStick.STICK_STATE.NO_MOVEMENT;
            notifyOnRevoke();

            // not longer pressed reset analog stick
            notifyOnMovement(0, 0);
        }
        // refresh view
        invalidate();
        // accept the touch event
        return true;
    }

    public void setElementMiddleValue(String middleValue) {
        this.middleValue = middleValue;
        middleValueSendHandler = elementController.getSendEventHandler(middleValue);
    }

    public void setElementUpValue(String upValue) {
        this.upValue = upValue;
        upValueSendHandler = elementController.getSendEventHandler(upValue);
    }

    public void setElementDownValue(String downValue) {
        this.downValue = downValue;
        downValueSendHandler = elementController.getSendEventHandler(downValue);
    }

    public void setElementLeftValue(String leftValue) {
        this.leftValue = leftValue;
        leftValueSendHandler = elementController.getSendEventHandler(leftValue);
    }

    public void setElementRightValue(String rightValue) {
        this.rightValue = rightValue;
        rightValueSendHandler = elementController.getSendEventHandler(rightValue);
    }

    public void setElementRadius(int radius) {
        this.radius = radius;
        radius_complete = getPercent(radius, 100) - 2 * thick;
        radius_dead_zone = getPercent(radius, deadZoneRadius);
        radius_analog_stick = getPercent(radius, 20);
        invalidate();
    }

    public void setElementDeadZoneRadius(int deadZoneRadius) {
        this.deadZoneRadius = deadZoneRadius;
        radius_dead_zone = getPercent(radius, deadZoneRadius);
        invalidate();
    }

    public void setElementThick(int thick) {
        this.thick = thick;
        invalidate();
    }

    public void setElementNormalColor(int normalColor) {
        this.normalColor = normalColor;
        invalidate();
    }

    public void setElementPressedColor(int pressedColor) {
        this.pressedColor = pressedColor;
        invalidate();
    }

    public void setElementBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        invalidate();
    }

    public static ContentValues getInitialInfo(){
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE,ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK);
        contentValues.put(COLUMN_STRING_ELEMENT_UP_VALUE,"k51");
        contentValues.put(COLUMN_STRING_ELEMENT_DOWN_VALUE,"k47");
        contentValues.put(COLUMN_STRING_ELEMENT_LEFT_VALUE,"k29");
        contentValues.put(COLUMN_STRING_ELEMENT_RIGHT_VALUE,"k32");
        contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE,"k59");
        contentValues.put(COLUMN_INT_ELEMENT_DEAD_ZONE_RADIUS,30);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH,400);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT,400);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER,45);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,400);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y,400);
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS,100);
        contentValues.put(COLUMN_INT_ELEMENT_THICK,5);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,0xF0888888);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,0xF00000FF);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,0x00FFFFFF);
        return contentValues;


    }
}
