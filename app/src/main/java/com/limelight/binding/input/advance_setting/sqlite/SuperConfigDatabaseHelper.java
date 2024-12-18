package com.limelight.binding.input.advance_setting.sqlite;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Vibrator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.limelight.binding.input.advance_setting.config.PageConfigController;
import com.limelight.binding.input.advance_setting.element.Element;
import com.limelight.utils.MathUtils;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SuperConfigDatabaseHelper extends SQLiteOpenHelper {
    private class ExportFile{
        private int version;
        private String settings;
        private String elements;
        private String md5;

        public ExportFile(int version, String settings, String elements) {
            this.version = version;
            this.settings = settings;
            this.elements = elements;
            this.md5 = MathUtils.computeMD5(version + settings + elements);
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public String getSettings() {
            return settings;
        }

        public void setSettings(String settings) {
            this.settings = settings;
        }

        public String getElements() {
            return elements;
        }

        public void setElements(String elements) {
            this.elements = elements;
        }

        public String getMd5() {
            return md5;
        }

        public void setMd5(String md5) {
            this.md5 = md5;
        }
    }

    public class ContentValuesSerializer implements JsonSerializer<ContentValues>, JsonDeserializer<ContentValues> {

        @Override
        public JsonElement serialize(ContentValues src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            for (Map.Entry<String, Object> entry : src.valueSet()) {
                Object value = entry.getValue();
                if (value instanceof Integer) {
                    jsonObject.addProperty(entry.getKey(), (Integer) value);
                } else if (value instanceof Long) {
                    jsonObject.addProperty(entry.getKey(), (Long) value);
                } else if (value instanceof Double) {
                    jsonObject.addProperty(entry.getKey(), (Double) value);
                } else if (value instanceof String) {
                    jsonObject.addProperty(entry.getKey(), (String) value);
                } else if (value instanceof byte[]) {
                    // Serialize Blob as Base64 encoded string
                    String base64Blob = context.serialize(value).getAsString();
                    jsonObject.addProperty(entry.getKey(), base64Blob);
                }
                // Handle other types as needed
            }
            return jsonObject;
        }

        @Override
        public ContentValues deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            ContentValues contentValues = new ContentValues();
            JsonObject jsonObject = json.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                JsonElement jsonElement = entry.getValue();
                if (jsonElement.isJsonPrimitive()) {
                    JsonPrimitive jsonPrimitive = jsonElement.getAsJsonPrimitive();
                    if (jsonPrimitive.isNumber()) {
                        // Determine if it's a Long or Double based on the value
                        if (jsonPrimitive.getAsString().contains(".")) {
                            contentValues.put(entry.getKey(), jsonPrimitive.getAsDouble());
                        } else {
                            contentValues.put(entry.getKey(), jsonPrimitive.getAsLong());
                        }
                    } else if (jsonPrimitive.isString()) {
                        contentValues.put(entry.getKey(), jsonPrimitive.getAsString());
                    }
                } else if (jsonElement.isJsonArray()) {
                    // Deserialize Blob from Base64 encoded string
                    byte[] blob = context.deserialize(jsonElement, byte[].class);
                    contentValues.put(entry.getKey(), blob);
                }
                // Handle other types as needed
            }
            return contentValues;
        }
    }


    private static final String DATABASE_NAME = "super_config.db";
    private static final int DATABASE_OLD_VERSION_1 = 1;
    private static final int DATABASE_OLD_VERSION_2 = 2;
    private static final int DATABASE_VERSION = 3;
    private SQLiteDatabase writableDataBase;
    private SQLiteDatabase readableDataBase;

    public SuperConfigDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        writableDataBase = getWritableDatabase();
        readableDataBase = getReadableDatabase();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // 创建表格的SQL语句
        String createElementTable = "CREATE TABLE IF NOT EXISTS element (" +
                "_id INTEGER PRIMARY KEY, " +
                "element_id INTEGER," +
                "config_id INTEGER," +
                "element_type INTEGER," +
                "element_value TEXT," +
                "element_middle_value TEXT," +
                "element_up_value TEXT," +
                "element_down_value TEXT," +
                "element_left_value TEXT," +
                "element_right_value TEXT," +
                "element_layer INTEGER," +
                "element_mode INTEGER," +
                "element_sense INTEGER," +
                "element_central_x INTEGER," +
                "element_central_y INTEGER," +
                "element_width INTEGER," +
                "element_height INTEGER," +
                "element_area_width INTEGER," +
                "element_area_height INTEGER," +
                "element_text TEXT," +
                "element_click_text TEXT," +
                "element_background_icon TEXT," +
                "element_click_background_icon TEXT," +
                "element_radius INTEGER," +
                "element_opacity INTEGER," +
                "element_thick INTEGER," +
                "element_background_color INTEGER," +
                "element_color INTEGER," +
                "element_pressed_color INTEGER," +
                "element_create_time INTEGER" +
                ")";

        // 执行SQL语句
        db.execSQL(createElementTable);

        String createConfigTable = "CREATE TABLE IF NOT EXISTS config (" +
                "_id INTEGER PRIMARY KEY, " +
                "config_id INTEGER," +
                "config_name TEXT," +
                "touch_enable TEXT," +
                "touch_mode TEXT," +
                "touch_sense INTEGER," +
                "game_vibrator TEXT," +
                "button_vibrator TEXT" +
                ")";

        db.execSQL(createConfigTable);
    }
    public void deleteTable(String tableName){
        SQLiteDatabase db = this.getWritableDatabase();
        db.execSQL("DROP TABLE IF EXISTS " + tableName);
    }



    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 升级数据库时执行的操作
        System.out.println("SuperConfigDatabaseHelper.onUpgrade");
        if (oldVersion == 2){
            db.execSQL("ALTER TABLE config ADD COLUMN game_vibrator TEXT DEFAULT 'false';");
            db.execSQL("ALTER TABLE config ADD COLUMN button_vibrator TEXT DEFAULT 'false';");
        }
    }

    public void insertElement(ContentValues values){
        writableDataBase.insert("element",null,values);
    }

    public void deleteElement(long configId,long elementId){

        // 定义 WHERE 子句
        String selection = "config_id = ? AND element_id = ?";
        // 定义 WHERE 子句中的参数
        String[] selectionArgs = { String.valueOf(configId), String.valueOf(elementId) };

        // 执行删除操作
        writableDataBase.delete("element", selection, selectionArgs);
    }

    public void updateElement(long configId,long elementId,ContentValues values){

        // 定义 WHERE 子句
        String selection = "config_id = ? AND element_id = ?";
        // 定义 WHERE 子句中的参数
        String[] selectionArgs = { String.valueOf(configId), String.valueOf(elementId) };

        writableDataBase.update(
                "element",   // 要更新的表
                values,    // 新值
                selection, // WHERE 子句
                selectionArgs // WHERE 子句中的占位符值
        );
    }

    public List<Long> queryAllElementIds(long configId){

        // 定义要查询的列
        String[] projection = { "element_id", "element_layer" };

        // 定义 WHERE 子句
        String selection = "config_id = ?";
        // 定义 WHERE 子句中的参数
        String[] selectionArgs = { String.valueOf(configId) };
        // 排序方式，增序
        String orderBy = "element_id + (element_layer * 281474976710656) ASC";

        // 执行查询
        Cursor cursor = readableDataBase.query(
                "element",   // 表名
                projection, // 要查询的列
                selection,  // WHERE 子句
                selectionArgs, // WHERE 子句中的参数
                null, // 不分组
                null, // 不过滤
                orderBy  // 增序排序
        );

        List<Long> elementIds = new ArrayList<>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                int elementIdIndex = cursor.getColumnIndexOrThrow("element_id");
                long elementId = cursor.getLong(elementIdIndex);
                elementIds.add(elementId);
            }
            cursor.close();
        }
        System.out.println("elementIds = " + elementIds);
        return elementIds;
    }
    public Object queryElementAttribute(long configId,long elementId,String elementAttribute){

        // 定义要查询的列
        String[] projection = { elementAttribute };

        // 定义 WHERE 子句
        String selection = "config_id = ? AND element_id = ?";
        // 定义 WHERE 子句中的参数
        String[] selectionArgs = { String.valueOf(configId), String.valueOf(elementId) };

        // 执行查询
        Cursor cursor = readableDataBase.query(
                "element",   // 表名
                projection, // 要查询的列
                selection,  // WHERE 子句
                selectionArgs, // WHERE 子句中的参数
                null, // 不分组
                null, // 不过滤
                null  // 不排序
        );

        Object o = null;
        if (cursor != null) {
            while (cursor.moveToNext()) {
                int columnIndex = cursor.getColumnIndexOrThrow(elementAttribute);
                switch (cursor.getType(columnIndex)) {
                    case Cursor.FIELD_TYPE_INTEGER:
                        o = cursor.getLong(columnIndex);
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        o = cursor.getDouble(columnIndex);
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                        o = cursor.getString(columnIndex);
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        o = cursor.getBlob(columnIndex);
                        break;
                    case Cursor.FIELD_TYPE_NULL:
                        break;
                }

            }
            cursor.close();
        }
        
        return o;
    }

    public Map<String, Object> queryAllElementAttributes(long configId,long elementId){
        Map<String, Object> resultMap = new HashMap<>();
        // 定义 WHERE 子句
        String selection = "config_id = ? AND element_id = ?";
        // 定义 WHERE 子句中的参数
        String[] selectionArgs = { String.valueOf(configId), String.valueOf(elementId) };

        // 执行查询
        Cursor cursor = readableDataBase.query(
                "element",   // 表名
                null, // 要查询的列
                selection,  // WHERE 子句
                selectionArgs, // WHERE 子句中的参数
                null, // 不分组
                null, // 不过滤
                null  // 不排序
        );
        if (cursor.moveToFirst()) {
            int columnCount = cursor.getColumnCount();
            for (int i = 0; i < columnCount; i++) {
                String columnName = cursor.getColumnName(i);
                int columnType = cursor.getType(i);

                switch (columnType) {
                    case Cursor.FIELD_TYPE_INTEGER:
                        resultMap.put(columnName, cursor.getLong(i));
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                        resultMap.put(columnName, cursor.getString(i));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        resultMap.put(columnName, cursor.getDouble(i));
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        resultMap.put(columnName, cursor.getBlob(i));
                        break;
                    case Cursor.FIELD_TYPE_NULL:
                        break;
                }
            }
        }
        cursor.close();
        return resultMap;
    }

    public void insertConfig(ContentValues values){

        writableDataBase.insert("config",null,values);
        
    }

    public void deleteConfig(long configId){

        // 定义 WHERE 子句
        String selection = "config_id = ?";
        // 定义 WHERE 子句中的参数
        String[] selectionArgs = { String.valueOf(configId) };

        // 执行删除操作
        writableDataBase.delete("config", selection, selectionArgs);

        //删除element表中所有的config_id的element
        writableDataBase.delete("element", selection, selectionArgs);
        
    }

    public void updateConfig(long configId,ContentValues values){

        // SQL WHERE 子句
        String selection = "config_id = ?";
        // selectionArgs 数组提供了 WHERE 子句中占位符 ? 的实际值
        String[] selectionArgs = { String.valueOf(configId) };

        writableDataBase.update(
                "config",   // 要更新的表
                values,    // 新值
                selection, // WHERE 子句
                selectionArgs // WHERE 子句中的占位符值
        );
        

    }

    public List<Long> queryAllConfigIds(){

        // 定义要查询的列
        String[] projection = { "config_id" };
        // 排序方式，增序
        String orderBy = "config_id ASC";
        // 执行查询
        Cursor cursor = readableDataBase.query(
                "config",   // 表名
                projection, // 要查询的列
                null,  // WHERE 子句
                null, // WHERE 子句中的参数
                null, // 不分组
                null, // 不过滤
                orderBy  // 增序
        );

        List<Long> configIds = new ArrayList<>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                int configIdIndex = cursor.getColumnIndexOrThrow("config_id");
                long configId = cursor.getLong(configIdIndex);
                configIds.add(configId);
            }
            cursor.close();
        }
        
        System.out.println("configIds = " + configIds);
        return configIds;
    }

    public Object queryConfigAttribute(long configId,String configAttribute,Object defaultValue){

        // 定义要查询的列
        String[] projection = { configAttribute };

        // 定义 WHERE 子句
        String selection = "config_id = ?";
        // 定义 WHERE 子句中的参数
        String[] selectionArgs = { String.valueOf(configId) };

        // 执行查询
        Cursor cursor = readableDataBase.query(
                "config",   // 表名
                projection, // 要查询的列
                selection,  // WHERE 子句
                selectionArgs, // WHERE 子句中的参数
                null, // 不分组
                null, // 不过滤
                null  // 不排序
        );

        Object o = null;
        if (cursor != null) {
            while (cursor.moveToNext()) {
                int columnIndex = cursor.getColumnIndexOrThrow(configAttribute);
                switch (cursor.getType(columnIndex)) {
                    case Cursor.FIELD_TYPE_INTEGER:
                        o = cursor.getLong(columnIndex);
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        o = cursor.getDouble(columnIndex);
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                        o = cursor.getString(columnIndex);
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        o = cursor.getBlob(columnIndex);
                        break;
                    case Cursor.FIELD_TYPE_NULL:
                        break;
                }

            }
            cursor.close();
        }
        if (o == null){
            return defaultValue;
        }
        return o;
    }

    public String exportConfig(Long configId){
        List<ContentValues> elementsValueList = new ArrayList<>();
        ContentValues settingValues = new ContentValues();

        // 定义 WHERE 子句
        String selection = "config_id = ?";

        // 定义 WHERE 子句中的参数
        String[] selectionArgs = { String.valueOf(configId) };

        Cursor cursor = readableDataBase.query(
                "element",   // 表名
                null, // 要查询的列
                selection,  // WHERE 子句
                selectionArgs, // WHERE 子句中的参数
                null, // 不分组
                null, // 不过滤
                null  // 不排序
        );

        // 遍历查询结果
        if (cursor.moveToFirst()) {
            do {
                ContentValues contentValues = new ContentValues();

                // 将当前行的所有数据存入 ContentValues
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    String columnName = cursor.getColumnName(i);
                    if (columnName.equals("_id")){
                        continue;
                    }
                    int type = cursor.getType(i);

                    // 根据列的数据类型将其添加到 ContentValues
                    switch (type) {
                        case Cursor.FIELD_TYPE_INTEGER:
                            contentValues.put(columnName, cursor.getLong(i));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            contentValues.put(columnName, cursor.getDouble(i));
                            break;
                        case Cursor.FIELD_TYPE_STRING:
                            contentValues.put(columnName, cursor.getString(i));
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            contentValues.put(columnName, cursor.getBlob(i));
                            break;
                        case Cursor.FIELD_TYPE_NULL:
                            break;
                    }
                }

                // 将 ContentValues 对象添加到结果列表中
                elementsValueList.add(contentValues);

            } while (cursor.moveToNext());
        }



        cursor = readableDataBase.query(
                "config",   // 表名
                null, // 要查询的列
                selection,  // WHERE 子句
                selectionArgs, // WHERE 子句中的参数
                null, // 不分组
                null, // 不过滤
                null  // 不排序
        );

        if (cursor.moveToFirst()) {
            // 将当前行的所有数据存入 ContentValues
            for (int i = 0; i < cursor.getColumnCount(); i++) {
                String columnName = cursor.getColumnName(i);
                if (columnName.equals("_id")){
                    continue;
                }
                int type = cursor.getType(i);

                // 根据列的数据类型将其添加到 ContentValues
                switch (type) {
                    case Cursor.FIELD_TYPE_INTEGER:
                        settingValues.put(columnName, cursor.getLong(i));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        settingValues.put(columnName, cursor.getDouble(i));
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                        settingValues.put(columnName, cursor.getString(i));
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        settingValues.put(columnName, cursor.getBlob(i));
                        break;
                    case Cursor.FIELD_TYPE_NULL:
                        break;
                }
            }
        }

        // 关闭 Cursor
        cursor.close();

        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(ContentValues.class, new ContentValuesSerializer());
        Gson gson = gsonBuilder.create();
        ContentValues[] elementsValues = elementsValueList.toArray(new ContentValues[0]);


        String settingString = gson.toJson(settingValues);
        String elementsString = gson.toJson(elementsValues);


        return gson.toJson(new ExportFile(DATABASE_VERSION,settingString,elementsString));



    }

    public int importConfig(String configString){
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(ContentValues.class, new ContentValuesSerializer());
        Gson gson = gsonBuilder.create();
        ExportFile exportFile;
        int version;
        String settingString;
        String elementsString;
        String md5;
        try {
            exportFile = gson.fromJson(configString, ExportFile.class);
            version = exportFile.getVersion();
            settingString = exportFile.getSettings();
            elementsString = exportFile.getElements();
            md5 = exportFile.getMd5();
        } catch (Exception e){
            return -1;
        }



        if (!md5.equals(MathUtils.computeMD5(version + settingString + elementsString))){
            return -2;
        }
        if (version == DATABASE_OLD_VERSION_1){
            // 正则表达式
            String regex = "(\"element_type\":)\\s*51";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(elementsString);
            // 检查是否有匹配项
            if (matcher.find()) {
                // 替换51为3
                elementsString = matcher.replaceAll("$13");// 输出: {"element_type":3, "other_key":123}
            }
        } else if (version == DATABASE_OLD_VERSION_2){

        } else if (version != DATABASE_VERSION){
            return -3;
        }
        ContentValues settingValues = gson.fromJson(settingString, ContentValues.class);
        ContentValues[] elements = gson.fromJson(elementsString, ContentValues[].class);

        // 将组按键及其子按键存储在MAP中
        Map<ContentValues,List<ContentValues>> groupButtonMaps = new HashMap<>();
        for (ContentValues groupButtonElement : elements){
            if ((long)groupButtonElement.get(Element.COLUMN_INT_ELEMENT_TYPE) == Element.ELEMENT_TYPE_GROUP_BUTTON){
                List<ContentValues> childElements = new ArrayList<>();

                String[] childElementStringIds = ((String) groupButtonElement.get(Element.COLUMN_STRING_ELEMENT_VALUE)).split(",");
                // 按键组的值，子按键们的ID
                for (String childElementStringId : childElementStringIds){
                    long childElementId = Long.parseLong(childElementStringId);
                    for (ContentValues element : elements){
                        if ((long)element.get(Element.COLUMN_LONG_ELEMENT_ID) == childElementId){
                            childElements.add(element);
                            break;
                        }
                    }
                }
                groupButtonMaps.put(groupButtonElement,childElements);

            }
        }


        Long newConfigId = System.currentTimeMillis();
        settingValues.put(PageConfigController.COLUMN_LONG_CONFIG_ID,newConfigId);
        insertConfig(settingValues);

        // 更新所有按键的ID
        long elementId = System.currentTimeMillis();
        for (ContentValues contentValues : elements){
            contentValues.put(Element.COLUMN_LONG_ELEMENT_ID,elementId ++);
            contentValues.put(Element.COLUMN_LONG_CONFIG_ID,newConfigId);
            insertElement(contentValues);
        }

        // 更新组按键的值
        for (Map.Entry<ContentValues, List<ContentValues>> groupButtonMap : groupButtonMaps.entrySet()) {
            String newValue = "-1";
            for (ContentValues childElement : groupButtonMap.getValue()){
                newValue = newValue + "," + childElement.get(Element.COLUMN_LONG_ELEMENT_ID);
            }
            ContentValues groupButton = groupButtonMap.getKey();
            groupButton.put(Element.COLUMN_STRING_ELEMENT_VALUE,newValue);
            updateElement(  (Long) groupButton.get(Element.COLUMN_LONG_CONFIG_ID),
                    (Long) groupButton.get(Element.COLUMN_LONG_ELEMENT_ID),
                    groupButton);
        }

        return 0;
    }

    public int mergeConfig(String configString,Long existConfigId){
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(ContentValues.class, new ContentValuesSerializer());
        Gson gson = gsonBuilder.create();
        ExportFile exportFile;
        int version;
        String settingString;
        String elementsString;
        String md5;
        try {
            exportFile = gson.fromJson(configString, ExportFile.class);
            version = exportFile.getVersion();
            settingString = exportFile.getSettings();
            elementsString = exportFile.getElements();
            md5 = exportFile.getMd5();
        } catch (Exception e){
            return -1;
        }



        if (!md5.equals(MathUtils.computeMD5(version + settingString + elementsString))){
            return -2;
        }

        if (version == DATABASE_OLD_VERSION_1){
            // 正则表达式
            String regex = "(\"element_type\":)\\s*51";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(elementsString);
            // 检查是否有匹配项
            if (matcher.find()) {
                // 替换51为3
                elementsString = matcher.replaceAll("$13");// 输出: {"element_type":3, "other_key":123}
            }
        } else if (version == DATABASE_OLD_VERSION_2){

        } else if (version != DATABASE_VERSION){
            return -3;
        }

        ContentValues[] elements = gson.fromJson(elementsString, ContentValues[].class);

        // 将组按键及其子按键存储在MAP中
        Map<ContentValues,List<ContentValues>> groupButtonMaps = new HashMap<>();
        for (ContentValues groupButtonElement : elements){
            if ((long)groupButtonElement.get(Element.COLUMN_INT_ELEMENT_TYPE) == Element.ELEMENT_TYPE_GROUP_BUTTON){
                List<ContentValues> childElements = new ArrayList<>();

                String[] childElementStringIds = ((String) groupButtonElement.get(Element.COLUMN_STRING_ELEMENT_VALUE)).split(",");
                // 按键组的值，子按键们的ID
                for (String childElementStringId : childElementStringIds){
                    long childElementId = Long.parseLong(childElementStringId);
                    for (ContentValues element : elements){
                        if ((long)element.get(Element.COLUMN_LONG_ELEMENT_ID) == childElementId){
                            childElements.add(element);
                            break;
                        }
                    }
                }
                groupButtonMaps.put(groupButtonElement,childElements);

            }
        }

        // 更新所有按键的ID
        long elementId = System.currentTimeMillis();
        for (ContentValues contentValues : elements){
            contentValues.put(Element.COLUMN_LONG_ELEMENT_ID,elementId ++);
            contentValues.put(Element.COLUMN_LONG_CONFIG_ID,existConfigId);
            insertElement(contentValues);
        }

        // 更新组按键的值
        for (Map.Entry<ContentValues, List<ContentValues>> groupButtonMap : groupButtonMaps.entrySet()) {
            String newValue = "-1";
            for (ContentValues childElement : groupButtonMap.getValue()){
                newValue = newValue + "," + childElement.get(Element.COLUMN_LONG_ELEMENT_ID);
            }
            ContentValues groupButton = groupButtonMap.getKey();
            groupButton.put(Element.COLUMN_STRING_ELEMENT_VALUE,newValue);
            updateElement(  (Long) groupButton.get(Element.COLUMN_LONG_CONFIG_ID),
                            (Long) groupButton.get(Element.COLUMN_LONG_ELEMENT_ID),
                            groupButton);
        }

        return 0;
    }


}

