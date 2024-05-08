package com.virjar.tk.server.utils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Md5Utils {

    // 这里名字不能是md5，要不然很多人会认为输出应该是字符串，导致使用错误
    public static byte[] md5Bytes(String key) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(key.getBytes(StandardCharsets.UTF_8));
            return md5.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("will not happen", e);
        }
    }

    public static String getHashWithInputStream(InputStream inputStream) {
        try {
            byte[] buffer = new byte[1000];
            MessageDigest md5 = MessageDigest.getInstance("MD5");

            int numRead;
            while ((numRead = inputStream.read(buffer)) > 0) {
                md5.update(buffer, 0, numRead);
            }

            inputStream.close();
            return toHexString(md5.digest());
        } catch (Exception var4) {
            throw new IllegalStateException(var4);
        }
    }

    public static long seedLong(String key) {
        byte[] digest = md5Bytes(key);
        return
                (digest[0] & 0xFFL) << 56
                        | (digest[1] & 0xFFL) << 48
                        | (digest[2] & 0xFFL) << 40
                        | (digest[3] & 0xFFL) << 32
                        | (digest[4] & 0xFFL) << 24
                        | (digest[5] & 0xFFL) << 16
                        | (digest[6] & 0xFFL) << 8
                        | (digest[7] & 0xFFL);
    }

    private static final char[] hexChar = new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};


    public static String md5Hex(String input) {
        return toHexString(md5Bytes(input));
    }

    public static String toHexString(byte[] b) {
        return toHexString(b, 0, b.length);
    }

    public static String toHexString(byte[] b, int start, int len) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < len; i++) {
            byte b1 = b[start + i];
            sb.append(hexChar[(b1 & 240) >>> 4]);
            sb.append(hexChar[b1 & 15]);
        }

        return sb.toString();
    }

    public static byte[] hexToByteArray(String inHex) {
        int hexlen = inHex.length();
        byte[] result;
        if (hexlen % 2 == 1) {
            //奇数
            hexlen++;
            result = new byte[(hexlen / 2)];
            inHex = "0" + inHex;
        } else {
            //偶数
            result = new byte[(hexlen / 2)];
        }
        int j = 0;
        for (int i = 0; i < hexlen; i += 2) {
            result[j] = hexToByte(inHex.substring(i, i + 2));
            j++;
        }
        return result;
    }

    public static byte hexToByte(String inHex) {
        return (byte) Integer.parseInt(inHex, 16);
    }
}
