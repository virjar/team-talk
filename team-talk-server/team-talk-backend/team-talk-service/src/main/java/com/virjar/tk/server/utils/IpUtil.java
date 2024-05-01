package com.virjar.tk.server.utils;

import com.virjar.tk.server.utils.net.SimpleHttpInvoker;
import com.virjar.tk.server.service.base.config.Settings;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.net.*;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IpUtil {
    private static final Pattern ipPattern = Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+");

    public static String getOutIp() {
        String outIp = SimpleHttpInvoker.get(Settings.outIpTestUrl.value);
        if (isValidIp(outIp)) {
            return outIp;
        }
        outIp = SimpleHttpInvoker.get("https://myip.ipip.net/");
        if (StringUtils.isBlank(outIp)) {
            return null;
        }
        Matcher matcher = ipPattern.matcher(outIp);
        if (matcher.find()) {
            String group = matcher.group();
            if (isValidIp(group)) {
                return group;
            }
        }

        return null;
    }

    private static boolean isValidIp(String ip) {
        if (StringUtils.isBlank(ip)) {
            return false;
        }
        ip = ip.trim();
        String[] segments = ip.split("\\.");
        if (segments.length != 4) {
            return false;
        }
        for (String segment : segments) {
            int i = NumberUtils.toInt(segment, -1);
            if (i < 0 || i > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析本机IP
     */
    public static String getLocalIps() throws SocketException {
        Set<String> ips = Sets.newHashSet();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (networkInterface.isLoopback() || networkInterface.isVirtual() || networkInterface.isPointToPoint()) {
                continue;
            }
            if (!networkInterface.isUp()) {
                continue;
            }
            Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
            while (inetAddresses.hasMoreElements()) {
                InetAddress inetAddress = inetAddresses.nextElement();
                if (inetAddress instanceof Inet4Address && !inetAddress.isLoopbackAddress()) {
                    ips.add(inetAddress.getHostAddress());
                }
            }
        }


        try (DatagramSocket datagramSocket = new DatagramSocket()) {
            datagramSocket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            InetAddress localAddress = datagramSocket.getLocalAddress();
            if (localAddress instanceof Inet4Address) {
                ips.add(localAddress.getHostAddress());
            }
        } catch (UnknownHostException ignore) {

        }


        if (ips.size() > 4) {
            // 有docker混部的设备，内网地址特别多,这个时候以A网网段划分，每个A网段取一个
            // 否则数据库字段长度不够，存不下，正常的极其理论上内网地址是不超过两个的才对
            Map<String, String> filterMap = Maps.newHashMap();
            ips.forEach(s -> {
                int dotIndex = s.indexOf(".");
                String aSegment = s.substring(0, dotIndex);
                if (aSegment.equals("192") || aSegment.equals("10") || aSegment.equals("172")) {
                    // 内网地址
                    filterMap.put(aSegment, s);
                } else {
                    // 外网地址则不进行切割，因为外网地址是重要的通信探测通道
                    filterMap.put(s, s);
                }
            });
            ips = new HashSet<>(filterMap.values());
        }

        return org.apache.commons.lang3.StringUtils.join(ips, ",");
    }
}
