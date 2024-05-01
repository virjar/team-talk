package com.virjar.tk.server.utils;

import com.virjar.tk.server.entity.CommonRes;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Slf4j
public class CommonUtils {

    public static final ZoneOffset zoneOffset = ZoneOffset.of("+8");

    public static String throwableToString(Throwable throwable) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (PrintStream printStream = new PrintStream(byteArrayOutputStream)) {
            throwable.printStackTrace(printStream);
        }
        return byteArrayOutputStream.toString();
    }


    public long dateTimeToTimestamp(LocalDateTime ldt) {
        return ldt.toInstant(zoneOffset).toEpochMilli();
    }

    public static Object autoFormat(String input) {
        if (StringUtils.isBlank(input)) {
            return input;
        }
        input = input.trim();
        if (input.startsWith("[") || input.startsWith("{")) {
            try {
                // 只对json格式进行美化，因为spring标准返回json结构，没有美化会导致json中嵌套json字符串，比较难看
                return JSON.parse(input);
            } catch (JSONException e) {
                //ignore
            }
        }
        return input;
    }

    public static void writeRes(HttpServletResponse responseHandler, CommonRes<?> commonRes) {
        responseHandler.setContentType("application/json;charset=utf8");
        try {
            ServletOutputStream outputStream = responseHandler.getOutputStream();
            outputStream.write(JSON.toJSONBytes(commonRes));
            outputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    public static JSONObject transformHttpServletRequest(HttpServletRequest httpServletRequest) throws IOException {
        String contentType = httpServletRequest.getContentType();

        Map<String, String[]> parameterMap = httpServletRequest.getParameterMap();
        JSONObject requestJson = new JSONObject();
        for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
            String[] value = entry.getValue();
            if (value == null || value.length == 0) {
                continue;
            }
            requestJson.put(entry.getKey(), value[0]);
        }
        if ("post".equalsIgnoreCase(httpServletRequest.getMethod())) {
            ContentType contentTypeObject = ContentType.from(contentType);
            assert contentTypeObject != null;
            String charset = contentTypeObject.getCharset();
            if (StringUtils.isBlank(charset)) {
                charset = StandardCharsets.UTF_8.name();
            }

            String requestJsonBody = IOUtils.toString(httpServletRequest.getInputStream(), charset);
            if (StringUtils.containsIgnoreCase(contentType, "application/json")) {
                JSONObject jsonObject = JSONObject.parseObject(requestJsonBody);
                for (String key : jsonObject.keySet()) {
                    requestJson.put(key, jsonObject.get(key));
                }
            } else {
                requestJson.put("__body", requestJsonBody);
            }
        }
        return requestJson;
    }

    public interface FileUploadAction<R> {
        R doAction(File file);
    }

    public static <R> R uploadToTempNoCheck(MultipartFile multipartFile, FileUploadAction<R> action) {
        try {
            return uploadToTemp(multipartFile, action);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static <R> R uploadToTemp(MultipartFile multipartFile, FileUploadAction<R> action) throws IOException {
        File file = Files.createTempFile("upload", ".bin").toFile();
        try {
            multipartFile.transferTo(file);
            return action.doAction(file);
        } finally {
            FileUtils.deleteQuietly(file);
        }
    }

    public static void responseFile(File file, String contentType, HttpServletResponse httpServletResponse) {
        responseFile(file, contentType, httpServletResponse, true);
    }

    public static void responseFile(File file, String contentType, HttpServletResponse httpServletResponse, boolean download) {
        if (file == null || !file.canRead()) {
            CommonUtils.writeRes(httpServletResponse, CommonRes.failed("system error,filed retrieve failed"));
            return;
        }

        if (httpServletResponse.isCommitted()) {
            // 文件同步可能需要时间，这个过程如果用户已经断开连接了
            return;
        }

        httpServletResponse.setCharacterEncoding("UTF-8");
        if (download) {
            httpServletResponse.setHeader("Content-Disposition", "attachment;filename=" + file.getName());
        }
        httpServletResponse.setHeader("Content-length", String.valueOf(file.length()));
        httpServletResponse.setContentType(contentType);

        try (BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(httpServletResponse.getOutputStream())) {
            IOUtils.copy(Files.newInputStream(file.toPath()), bufferedOutputStream);
        } catch (IOException e) {
            // 此时已经在写数据了,不能再返回其他数据
            log.error("write download file error", e);
        }
    }
}
