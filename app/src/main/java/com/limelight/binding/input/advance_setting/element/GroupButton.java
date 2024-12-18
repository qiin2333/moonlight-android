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
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Toast;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;
import com.limelight.binding.input.advance_setting.superpage.SuperPagesController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This is a digital button on screen element. It is used to get click and double click user input.
 */
public class GroupButton extends Element {

    private static final String COLUMN_INT_CHILD_VISIBILITY = COLUMN_INT_ELEMENT_SENSE;

    private static final int CHILD_VISIBLE = VISIBLE;
    private static final int CHILD_INVISIBLE = INVISIBLE;

    /**
     * Listener interface to update registered observers.
     */
    public interface GroupButtonListener {

        /**
         * onClick event will be fired on button click.
         */
        void onClick();

        /**
         * onLongClick event will be fired on button long click.
         */
        void onLongClick();

        /**
         * onRelease event will be fired on button unpress.
         */
        void onRelease();
    }

    private SuperConfigDatabaseHelper superConfigDatabaseHelper;
    private PageDeviceController pageDeviceController;
    private GroupButton groupButton;
    private List<Element> childElementList = new ArrayList<>();
    private ElementController elementController;
    private Context context;
    private SuperPagesController superPagesController;

    private GroupButtonListener listener;
    private String text;
    private String value;
    private int radius;
    private int thick;
    private int childVisibility;
    private int normalColor;
    private int pressedColor;
    private int backgroundColor;

    private float lastX;
    private float lastY;
    private boolean childPositionAttributeFollow = true;
    private boolean childOtherAttributeFollow = false;
    private final int initialCentralXMax;
    private final int initialCentralXMin;
    private final int initialCentralYMax;
    private final int initialCentralYMin;

    private SuperPageLayout groupButtonPage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;
    private boolean selectMode = false;

    private boolean movable = false;
    private boolean layoutComplete = true;
    private boolean resizeXBorder = true;
    private boolean resizeYBorder = true;

    private long timerLongClickTimeout = 800;
    private final Runnable longClickRunnable = new Runnable() {
        @Override
        public void run() {
            onLongClickCallback();
        }
    };
    private final Paint paintBorder = new Paint();
    private final Paint paintBackground = new Paint();
    private final Paint paintText = new Paint();
    private final Paint paintEdit = new Paint();
    private final RectF rect = new RectF();



    public GroupButton(Map<String,Object> attributesMap,
                       ElementController controller,
                       PageDeviceController pageDeviceController,
                       SuperPagesController superPagesController,
                       Context context) {
        super(attributesMap,controller,context);
        this.pageDeviceController = pageDeviceController;
        this.groupButton = this;
        this.elementController = controller;
        this.context = context;
        this.superPagesController = superPagesController;


        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Game)context).getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        initialCentralXMax = displayMetrics.widthPixels;
        initialCentralXMin = 0;
        initialCentralYMax = displayMetrics.heightPixels;
        initialCentralYMin = 0;
        super.centralXMax  = initialCentralXMax;
        super.centralXMin  = initialCentralXMin;
        super.centralYMax  = initialCentralYMax;
        super.centralYMin  = initialCentralYMin;
        super.widthMax  = displayMetrics.widthPixels / 2;
        super.widthMin  = 50;
        super.heightMax  = displayMetrics.heightPixels / 2;
        super.heightMin  = 50;

        paintEdit.setStyle(Paint.Style.STROKE);
        paintEdit.setStrokeWidth(4);
        paintEdit.setPathEffect(new DashPathEffect(new float[]{10, 20}, 0));
        paintText.setTextAlign(Paint.Align.CENTER);
        paintBorder.setStyle(Paint.Style.STROKE);
        paintBackground.setStyle(Paint.Style.FILL);


        text = (String) attributesMap.get(COLUMN_STRING_ELEMENT_TEXT);
        radius = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_RADIUS)).intValue();
        thick = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_THICK)).intValue();
        childVisibility = ((Long) attributesMap.get(COLUMN_INT_CHILD_VISIBILITY)).intValue();
        normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
        pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
        backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
        value = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE);

        String[] childElementIds = value.split(",");
        List<Element> allElements = elementController.getElements();
        StringBuilder newValue = new StringBuilder("-1");
        for (String childElementIdString : childElementIds){
            Long childElementId = Long.parseLong(childElementIdString);
            for (Element element : allElements){
                if (element.elementId.equals(childElementId)){
                    childElementList.add(element);
                    newValue.append(",").append(childElementId);
                }
            }
        }
        value = newValue.toString();
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE,value);
        elementController.updateElement(elementId,contentValues);
        setElementChildVisibility(childVisibility);

        listener = new GroupButtonListener() {
            @Override
            public void onClick() {

            }

            @Override
            public void onLongClick() {


            }

            @Override
            public void onRelease() {
                if (childVisibility == CHILD_VISIBLE){
                    setElementChildVisibility(CHILD_INVISIBLE);
                } else {
                    setElementChildVisibility(CHILD_VISIBLE);
                }
                save();
            }
        };
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        layoutComplete = true;
        super.onLayout(changed, left, top, right, bottom);
    }

    @Override
    protected void onElementDraw(Canvas canvas) {

        // 文字
        int elementWidth = getElementWidth();
        int elementHeight = getElementHeight();
        float textSize = getPercent(elementWidth, 25);
        textSize = Math.min(textSize,getPercent(elementHeight,63));
        paintText.setTextSize(textSize);
        paintText.setColor(isPressed() ? pressedColor : normalColor);
        // 边框
        paintBorder.setStrokeWidth(thick);
        paintBorder.setColor(isPressed() ? pressedColor : normalColor);
        // 背景颜色
        paintBackground.setColor(backgroundColor);
        // 绘画范围
        rect.left = rect.top = (float) thick / 2;
        rect.right = getElementWidth() - rect.left;
        rect.bottom = getHeight() - rect.top;
        // 绘制背景
        canvas.drawRoundRect(rect, radius, radius, paintBackground);
        // 绘制边框
        canvas.drawRoundRect(rect, radius, radius, paintBorder);
        // 绘制文字
        canvas.drawText(text, getPercent(elementWidth, 50), getPercent(elementHeight, 63), paintText);

        if (elementController.getMode() == ElementController.Mode.Edit){
            // 绘画范围
            rect.left = rect.top = 2;
            rect.right = getWidth() - 2;
            rect.bottom = getHeight() - 2;
            // 边框
            paintEdit.setColor(editColor);
            canvas.drawRect(rect,paintEdit);

        } else if (elementController.getMode() == ElementController.Mode.Select && selectMode){
            // 选中的group button做标记
            // 绘画范围
            rect.left = rect.top = 2;
            rect.right = getWidth() - 2;
            rect.bottom = getHeight() - 2;
            // 边框
            paintEdit.setColor(0xff00f91a);
            canvas.drawRect(rect,paintEdit);
        }
    }

    private void onClickCallback() {
        // notify listenersbuttonListener.onClick();
        System.out.println("onClickCallback");
        listener.onClick();
        elementController.getHandler().removeCallbacks(longClickRunnable);
        elementController.getHandler().postDelayed(longClickRunnable, timerLongClickTimeout);

    }

    private void onLongClickCallback() {
        // notify listeners
        System.out.println("onLongClickCallback");
        listener.onLongClick();
        elementController.buttonVibrator();
        movable = true;
        if (childPositionAttributeFollow){
            for (Element element : childElementList){
                element.setAlpha(0.5f);
            }
        }
        invalidate();
    }

    private void onReleaseCallback() {
        // notify listeners
        System.out.println("onReleaseCallback");
        listener.onRelease();

        // We may be called for a release without a prior click
        elementController.getHandler().removeCallbacks(longClickRunnable);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {


        if (event.getActionIndex() != 0) {
            return true;
        }

        switch (elementController.getMode()){
            case Normal:
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        elementController.buttonVibrator();
                        resizeXBorder = true;
                        resizeYBorder = true;
                        lastX = event.getX();
                        lastY = event.getY();

                        setPressed(true);
                        onClickCallback();

                        invalidate();
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (movable) {
                            float x = event.getX();
                            float y = event.getY();
                            float deltaX = x - lastX;
                            float deltaY = y - lastY;
                            //小位移算作点击
                            if (Math.abs(deltaX) + Math.abs(deltaY) < 0.2){
                                return true;
                            }
                            if (layoutComplete){
                                layoutComplete = false;
                                setElementCentralX((int) getX() + getWidth() / 2 + (int) deltaX);
                                setElementCentralY((int) getY() + getHeight() / 2 + (int) deltaY);
                            }
                            updatePage();
                            return true;
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP: {
                        setPressed(false);
                        if (movable){
                            if (childPositionAttributeFollow){
                                for (Element element : childElementList){
                                    element.setAlpha(1);
                                    element.save();
                                }
                            }
                            movable = false;
                            invalidate();
                            save();

                        } else {
                            onReleaseCallback();
                        }
                        invalidate();

                        return true;
                    }
                    default: {
                    }
                }
                return true;
            case Edit:
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        resizeXBorder = true;
                        resizeYBorder = true;
                        lastX = event.getX();
                        lastY = event.getY();
                        editColor = 0xff00f91a;
                        invalidate();
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        float x = event.getX();
                        float y = event.getY();
                        float deltaX = x - lastX;
                        float deltaY = y - lastY;
                        //小位移算作点击
                        if (Math.abs(deltaX) + Math.abs(deltaY) < 0.2){
                            return true;
                        }
                        if (layoutComplete){
                            layoutComplete = false;
                            setElementCentralX((int) getX() + getWidth() / 2 + (int) deltaX);
                            setElementCentralY((int) getY() + getHeight() / 2 + (int) deltaY);
                        }
                        updatePage();
                        movable = true;
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_UP: {
                        if (movable){
                            if (childPositionAttributeFollow){
                                for (Element element : childElementList){
                                    element.save();
                                }
                            }
                            save();
                            movable = false;
                        } else {
                            elementController.toggleInfoPage(getInfoPage());
                        }

                        editColor = 0xffdc143c;
                        invalidate();

                        return true;
                    }
                    default: {
                    }
                }
                return true;
            case Select:
                return true;
        }
        return true;
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        return true;
    }

    @Override
    public void save() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, text);
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value);
        contentValues.put(COLUMN_INT_CHILD_VISIBILITY, childVisibility);
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
        if (groupButtonPage != null){
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }

    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (groupButtonPage == null){
            groupButtonPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_group_button,null);
            centralXNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_central_x);
            centralYNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_central_y);

        }

        NumberSeekbar widthNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_width);
        NumberSeekbar heightNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_height);
        NumberSeekbar radiusNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_radius);
        CheckBox childPositonAttributeFollowCheckBox = groupButtonPage.findViewById(R.id.page_group_button_child_position_attribute_follow);
        CheckBox childOtherAttributeFollowCheckBox = groupButtonPage.findViewById(R.id.page_group_button_child_other_attribute_follow);
        CheckBox childVisibleCheckBox = groupButtonPage.findViewById(R.id.page_group_button_child_visible);
        ElementEditText textElementEditText = groupButtonPage.findViewById(R.id.page_group_button_text);
        NumberSeekbar thickNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_thick);
        NumberSeekbar layerNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_layer);
        ElementEditText normalColorElementEditText = groupButtonPage.findViewById(R.id.page_group_button_normal_color);
        ElementEditText pressedColorElementEditText = groupButtonPage.findViewById(R.id.page_group_button_pressed_color);
        ElementEditText backgroundColorElementEditText = groupButtonPage.findViewById(R.id.page_group_button_background_color);
        EditText deleteEditText = groupButtonPage.findViewById(R.id.page_group_button_delete_edittext);
        Button deleteButton = groupButtonPage.findViewById(R.id.page_group_button_delete);

        textElementEditText.setTextWithNoTextChangedCallBack(text);
        textElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                setElementText(text);
                save();
            }
        });

        childPositonAttributeFollowCheckBox.setChecked(childPositionAttributeFollow);
        childPositonAttributeFollowCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                childPositionAttributeFollow = isChecked;
                if (!childPositionAttributeFollow){
                    centralXMax = initialCentralXMax;
                    centralXMin = initialCentralXMin;
                    centralYMax = initialCentralYMax;
                    centralYMin = initialCentralYMin;
                }

            }
        });
        childOtherAttributeFollowCheckBox.setChecked(childOtherAttributeFollow);
        childOtherAttributeFollowCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                childOtherAttributeFollow = isChecked;
            }
        });

        childVisibleCheckBox.setChecked(childVisibility == CHILD_VISIBLE);
        childVisibleCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setElementChildVisibility(isChecked ? CHILD_VISIBLE : CHILD_INVISIBLE);
                save();
            }
        });


        centralXNumberSeekbar.setProgressMin(centralXMin);
        centralXNumberSeekbar.setProgressMax(centralXMax);
        centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
        centralXNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (layoutComplete) {
                    layoutComplete = false;
                    setElementCentralX(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                resizeXBorder = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (childPositionAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
                save();
            }
        });
        centralYNumberSeekbar.setProgressMin(centralYMin);
        centralYNumberSeekbar.setProgressMax(centralYMax);
        centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        centralYNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (layoutComplete){
                    layoutComplete = false;
                    setElementCentralY(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                resizeYBorder = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (childPositionAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
                save();
            }
        });


        widthNumberSeekbar.setProgressMax(widthMax);
        widthNumberSeekbar.setProgressMin(widthMin);
        widthNumberSeekbar.setValueWithNoCallBack(getElementWidth());
        widthNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (layoutComplete) {
                    layoutComplete = false;
                    setElementWidth(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
                if (childOtherAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
                save();
            }
        });

        heightNumberSeekbar.setProgressMax(heightMax);
        heightNumberSeekbar.setProgressMin(heightMin);
        heightNumberSeekbar.setValueWithNoCallBack(getElementHeight());
        heightNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (layoutComplete) {
                    layoutComplete = false;
                    setElementHeight(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
                if (childOtherAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
                save();
            }
        });



        radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
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
                if (childOtherAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
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
                if (childOtherAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
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
                if (childOtherAttributeFollow){
                    for (Element element : childElementList){
                        element.save();
                    }
                }
                save();
            }
        });


        normalColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",normalColor));
        normalColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new Element.HexInputFilter()});
        normalColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    setElementNormalColor((int) Long.parseLong(text, 16));
                    if (childOtherAttributeFollow){
                        for (Element element : childElementList){
                            element.save();
                        }
                    }
                    save();
                }
            }
        });


        pressedColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",pressedColor));
        pressedColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new Element.HexInputFilter()});
        pressedColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    setElementPressedColor((int) Long.parseLong(text, 16));
                    if (childOtherAttributeFollow){
                        for (Element element : childElementList){
                            element.save();
                        }
                    }
                    save();
                }
            }
        });


        backgroundColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",backgroundColor));
        backgroundColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new Element.HexInputFilter()});
        backgroundColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    setElementBackgroundColor((int) Long.parseLong(text, 16));
                    if (childOtherAttributeFollow){
                        for (Element element : childElementList){
                            element.save();
                        }
                    }
                    save();
                }
            }
        });

        groupButtonPage.findViewById(R.id.page_group_button_select_child_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 改为选择模式
                elementController.changeMode(ElementController.Mode.Select);
                selectMode = true;
                // 设置按钮选择的回调函数
                ElementSelectedCallBack elementSelectedCallBack = new ElementSelectedCallBack() {
                    @Override
                    public void elementSelected(Element element) {
                        if (childElementList.contains(element)){
                            deleteChildElement(element);
                            element.setEditColor(EDIT_COLOR_SELECT);
                            element.invalidate();
                        } else {
                            addChildElement(element);
                            element.setEditColor(EDIT_COLOR_SELECTED);
                            element.invalidate();
                        }
                    }
                };
                for (Element element : childElementList){
                    element.setEditColor(EDIT_COLOR_SELECTED);
                }
                // 将子按键的编辑颜色设置为选中颜色
                for (Element element : elementController.getElements()){
                    element.setElementSelectedCallBack(elementSelectedCallBack);
                }
                // 打开空白页
                SuperPageLayout pageNull = superPagesController.getPageNull();
                superPagesController.openNewPage(pageNull);
                // 设置空白页返回的动作
                pageNull.setPageReturnListener(new SuperPageLayout.ReturnListener() {
                    @Override
                    public void returnCallBack() {
                        SuperPageLayout lastPage = pageNull.getLastPage();
                        // 用这个方法将pageNull的返回设置为editPage
                        elementController.open();
                        superPagesController.openNewPage(lastPage);
                        elementController.changeMode(ElementController.Mode.Edit);
                        selectMode = false;
                        save();
                    }
                });
            }
        });

        deleteButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (deleteEditText.getText().toString().equals("DELETE")){
                    List<Element> allElement = elementController.getElements();
                    for (Element element : childElementList){
                        if (!allElement.contains(element)){
                            continue;
                        }
                        elementController.deleteElement(element);
                    }
                    elementController.toggleInfoPage(groupButtonPage);
                    elementController.deleteElement(groupButton);
                    Toast.makeText(context,"删除成功",Toast.LENGTH_SHORT).show();
                }

            }
        });



        return groupButtonPage;
    }

    private void addChildElement(Element newElement){
        childElementList.add(newElement);
        value = "-1";
        for (Element element : childElementList){
            value = value + "," + element.elementId.toString();
        }
    }

    private void deleteChildElement(Element deleteElement){
        if (childElementList.remove(deleteElement)){
            value = "-1";
            for (Element element : childElementList){
                value = value + "," + element.elementId.toString();
            }
        }

    }


    protected void setElementText(String text) {
        this.text = text;
        invalidate();
    }

    @Override
    protected void setElementCentralX(int centralX) {
        if (childPositionAttributeFollow){
            int previousX = getElementCentralX();
            super.setElementCentralX(centralX);
            int deltaX = getElementCentralX() - previousX;
            for (Element element : childElementList){
                element.setElementCentralX(element.getElementCentralX() + deltaX);
            }
            if (resizeXBorder){
                int leftMargin = centralXMax;
                int rightMargin = centralXMax;
                List<Element> allElement = elementController.getElements();
                for (Element element : childElementList){
                    if (!allElement.contains(element)){
                        continue;
                    }
                    int elementCentralX = element.getElementCentralX();
                    leftMargin = Math.min(elementCentralX - element.centralXMin, leftMargin);
                    rightMargin = Math.min(element.centralXMax - elementCentralX, rightMargin);
                }
                int elementCentralX = getElementCentralX();
                leftMargin = Math.min(elementCentralX - centralXMin, leftMargin);
                rightMargin = Math.min(centralXMax - elementCentralX, rightMargin);

                centralXMin = elementCentralX - leftMargin;
                centralXMax = elementCentralX + rightMargin;
                if (centralXNumberSeekbar != null){
                    centralXNumberSeekbar.setProgressMin(centralXMin);
                    centralXNumberSeekbar.setProgressMax(centralXMax);
                }
                resizeXBorder = false;
            }
        } else {
            super.setElementCentralX(centralX);
        }

    }

    @Override
    protected void setElementCentralY(int centralY) {
        if (childPositionAttributeFollow){
            int previousY = getElementCentralY();
            super.setElementCentralY(centralY);
            int deltaY = getElementCentralY() - previousY;
            for (Element element : childElementList){
                element.setElementCentralY(element.getElementCentralY() + deltaY);
            }
            if (resizeYBorder){
                int bottomMargin = centralYMax;
                int topMargin = centralYMax;
                List<Element> allElement = elementController.getElements();
                for (Element element : childElementList){
                    if (!allElement.contains(element)){
                        continue;
                    }
                    int elementCentralY = element.getElementCentralY();
                    topMargin = Math.min(elementCentralY - element.centralYMin, topMargin);
                    bottomMargin = Math.min(element.centralYMax - elementCentralY, bottomMargin);
                }
                int elementCentralY = getElementCentralY();
                topMargin = Math.min(elementCentralY - centralYMin, topMargin);
                bottomMargin = Math.min(centralYMax - elementCentralY, bottomMargin);

                centralYMin = elementCentralY - topMargin;
                centralYMax = elementCentralY + bottomMargin;
                if (centralYNumberSeekbar != null){
                    centralYNumberSeekbar.setProgressMin(centralYMin);
                    centralYNumberSeekbar.setProgressMax(centralYMax);
                }
                resizeYBorder = false;
            }
        } else {
            super.setElementCentralY(centralY);
        }

    }

    @Override
    protected void setElementWidth(int width) {
        super.setElementWidth(width);
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        element.setElementWidth(width);
                        break;
                    default:
                        break;
                }
            }
        }

    }

    @Override
    protected void setElementHeight(int height) {
        super.setElementHeight(height);
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        element.setElementHeight(height);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    protected void setElementChildVisibility(int childVisibility){
        this.childVisibility = childVisibility;
        for (Element element : childElementList){
            element.setVisibility(childVisibility);
        }
    }

    protected void setElementRadius(int radius) {
        this.radius = radius;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementRadius(radius);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementRadius(radius);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementRadius(radius);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementRadius(radius);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    protected void setElementThick(int thick) {
        this.thick = thick;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_DIGITAL_PAD:
                        ((DigitalPad)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_ANALOG_STICK:
                        ((AnalogStick)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_DIGITAL_STICK:
                        ((DigitalStick)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                        ((InvisibleAnalogStick)element).setElementThick(thick);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                        ((InvisibleDigitalStick)element).setElementThick(thick);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    @Override
    protected void setElementLayer(int layer) {
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                element.setElementLayer(layer);
            }
        }
        super.setElementLayer(layer);
    }

    protected void setElementNormalColor(int normalColor) {
        this.normalColor = normalColor;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_PAD:
                        ((DigitalPad)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_ANALOG_STICK:
                        ((AnalogStick)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_STICK:
                        ((DigitalStick)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                        ((InvisibleAnalogStick)element).setElementNormalColor(normalColor);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                        ((InvisibleDigitalStick)element).setElementNormalColor(normalColor);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    protected void setElementPressedColor(int pressedColor) {
        this.pressedColor = pressedColor;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_PAD:
                        ((DigitalPad)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_ANALOG_STICK:
                        ((AnalogStick)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_STICK:
                        ((DigitalStick)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                        ((InvisibleAnalogStick)element).setElementPressedColor(pressedColor);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                        ((InvisibleDigitalStick)element).setElementPressedColor(pressedColor);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    protected void setElementBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        invalidate();
        if (childOtherAttributeFollow){
            for (Element element : childElementList){
                switch (element.elementType){
                    case ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                        ((DigitalCommonButton)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                        ((DigitalSwitchButton)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                        ((DigitalCombineButton)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                        ((DigitalMovableButton)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_PAD:
                        ((DigitalPad)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_ANALOG_STICK:
                        ((AnalogStick)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_DIGITAL_STICK:
                        ((DigitalStick)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                        ((InvisibleAnalogStick)element).setElementBackgroundColor(backgroundColor);
                        break;
                    case ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                        ((InvisibleDigitalStick)element).setElementBackgroundColor(backgroundColor);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    public static ContentValues getInitialInfo(){
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE,ELEMENT_TYPE_GROUP_BUTTON);
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT,"GROUP");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE,"-1");
        contentValues.put(COLUMN_INT_CHILD_VISIBILITY, VISIBLE);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH,100);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT,100);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER,50);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,100);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y,100);
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS,0);
        contentValues.put(COLUMN_INT_ELEMENT_THICK,5);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,0xF0888888);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,0xF00000FF);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,0x00FFFFFF);
        return contentValues;


    }

}
