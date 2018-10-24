package com.example.demo.common;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;
import org.codehaus.jackson.type.JavaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Locale;


/**
 * json处理工具
 *
 * @author jicexosl
 *         <p>
 *         2014-5-5
 */
public final class JsonUtil {
    private static final Logger LOG = LoggerFactory.getLogger(JsonUtil.class);

    private JsonUtil() {
    }

    /**
     * ObjectMapper 提供单例供全局使用
     */

    private static class SingletonHolder {
        private static ObjectMapper mapper;

        static {
            mapper = new ObjectMapper();
            //设置日期对象的输出格式
            mapper.setDateFormat(new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.CHINESE));
            //设置输入时忽略在JSON字符串中存在但Java对象实际没有的属性
            mapper.configure(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            //禁止使用int代表Enum的order()来反序列化Enum
            mapper.configure(DeserializationConfig.Feature.FAIL_ON_NUMBERS_FOR_ENUMS, true);
        }
    }

    private static ObjectMapper getMapper() {
        return SingletonHolder.mapper;
    }

    /**
     * 将对象转换为json字符串
     *
     * @param pojo
     * @return
     * @throws IOException
     */
    public static String toJsonString(Object pojo) throws IOException {
        if (pojo == null) {
            return null;
        }
        try {
            return getMapper().writeValueAsString(pojo);
        } catch (IOException e) {
            LOG.error("pojo parse  json string error", e);
            throw e;
        }
    }

    public static JsonNode toJson(Object pojo) throws IOException {
        if (pojo == null) {
            return null;
        }
        try {
            return getMapper().readTree(getMapper().writeValueAsString(pojo));
        } catch (IOException e) {
            LOG.error("pojo parse  JsonNode error", e);
            throw e;
        }
    }

    /**
     * 将字符串转换为json对象
     *
     * @param input
     * @return
     * @throws IOException
     */
    public static JsonNode parseJson(String input) throws IOException {
        if (input == null) {
            return null;
        }
        try {
            return getMapper().readTree(input);
        } catch (IOException e) {
            LOG.error("json processing error,input: " + input, e);
            throw e;
        }
    }


    /**
     * 将inputStream 转换为json对象
     *
     * @param in
     * @return
     * @throws IOException
     */
    public static JsonNode getJsonNodefromStream(InputStream in) throws IOException {
        try {
            return getMapper().readTree(in);
        } catch (JsonProcessingException e) {
            LOG.error("json processing error", e);
            throw e;
        } catch (IOException e) {
            LOG.error("read file error", e);
            throw e;
        }
    }

    /**
     * 将json字符串转换为java对象，只支持返回简单对象（非集合类型）
     *
     * @param jsonString
     * @param valueType
     * @return
     * @throws IOException
     */
    public static <T> T jsonToObject(String jsonString, Class<T> valueType) throws IOException {
        if (StringUtils.hasText(jsonString)) {
            return getMapper().readValue(jsonString, valueType);
        }
        return null;
    }

    /**
     * 将json字符串转换为java对象，只支持返回简单对象（非集合类型）
     *
     * @param jsonString
     * @return
     * @throws IOException
     */
    @SuppressWarnings("unchecked")
    public static HashMap<String, String> jsonToObjectAsHashMap(String jsonString) throws IOException {
        HashMap<String, String> paramMap = null;
        if (StringUtils.hasText(jsonString)) {
            paramMap = getMapper().readValue(jsonString, HashMap.class);
            //去除通用的
            paramMap.remove("clientInfo");
            paramMap.remove("authority");
            return paramMap;
        }
        return paramMap;
    }


    /**
     * 将json字符串转为集合类型 List、Map等
     *
     * @param jsonStr         json字符串
     * @param collectionClass 集合类型
     * @param elementClasses  泛型类型
     */
    public static <T> T jsonToObject(String jsonStr, Class<?> collectionClass, Class<?>... elementClasses) throws IOException {
        if (!StringUtils.hasText(jsonStr)) {
            return null;
        }
        JavaType javaType = getMapper().getTypeFactory().constructParametricType(collectionClass, elementClasses);
        return getMapper().readValue(jsonStr, javaType);
    }

    public static <T> T jsonToObject(JsonNode node, Class<?> collectionClass, Class<?>... elementClasses) throws IOException {
        if (node == null) {
            return null;
        }
        JavaType javaType = getMapper().getTypeFactory().constructParametricType(collectionClass, elementClasses);
        return getMapper().readValue(node, javaType);
    }

    /**
     * 创建一个空的json对象
     *
     * @return
     */
    public static ObjectNode createObjectNode() {
        return getMapper().createObjectNode();
    }

    /**
     * 创建一个空的json数组对象
     *
     * @return
     */
    public static ArrayNode createArrayNode() {
        return getMapper().createArrayNode();
    }

    /**
     * 生成json格式的响应结果
     *
     * @param msgCode
     * @param message
     * @param result
     * @return
     */
    public static String generateResponse(MsgCode msgCode, String message, Object result) {
        String response;
        ObjectNode node = createObjectNode();
        node.put("msgCode", msgCode.getMsgCode());
        if (message != null) {
            node.put("message", message);
        } else {
            node.put("message", msgCode.getMessage());
        }
        node.putPOJO("result", result);
        try {
            response = toJsonString(node);
        } catch (IOException e) {
            LOG.error("出错，e：", e);
            node = createObjectNode();
            node.put("msgCode", MsgCode.SYSTEM_ERROR.getMsgCode());
            response = node.toString();
        }
        LOG.info("返回结果：\n" + response);
        return response;
    }

    /**
     * 生成json格式的响应结果
     *
     * @param msgCode
     * @param message
     * @param result
     * @return
     */
    public static String generateResponse(Integer msgCode, String message, Object result) {
        String response;
        ObjectNode node = createObjectNode();
        node.putPOJO("msgCode", msgCode);
        node.putPOJO("message", message);
        node.putPOJO("result", result);
        try {
            response = toJsonString(node);
        } catch (IOException e) {
            node = createObjectNode();
            node.put("msgCode", MsgCode.SYSTEM_ERROR.getMsgCode());
            response = node.toString();
        }
        LOG.info("返回结果：\n" + response);
        return response;
    }


    public static String generateResponse(Integer msgCode, String message) {
        return generateResponse(msgCode, message, "");
    }

    public static String generateResponse(MsgCode msgCode, Object result) {
        return generateResponse(msgCode, null, result);
    }

    public static String generateSuccessResponse(Object result) {
        return generateResponse(MsgCode.SUCCESSFUL, null, result);
    }

    public static String generateResponse(String message, MsgCode msgCode) {
        return generateResponse(msgCode, message, null);
    }

    public static String generateResponse(String message, MsgCode msgCode, Object result) {
        return generateResponse(msgCode, message, result);
    }

    public static String generateResponse(MsgCode msgCode) {
        return generateResponse(msgCode, null, null);
    }

}
