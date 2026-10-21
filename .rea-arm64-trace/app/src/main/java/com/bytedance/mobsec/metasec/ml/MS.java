package com.bytedance.mobsec.metasec.ml;

public final class MS {
    private static byte[] certData;
    private static String storagePath;

    private MS() {}

    public static void configure(byte[] certificate, String path) {
        certData = certificate;
        storagePath = path;
    }

    public static Object b(int methodId, int arg2, long arg3, String arg4, Object arg5) {
        switch (methodId) {
            case 65539:
                return storagePath;
            case 33554433:
            case 33554434:
                return Boolean.TRUE;
            case 16777232:
                return 68132;
            case 16777233:
                return "6.8.1.32";
            case 16777218:
                return certData;
            case 268435470:
                return System.currentTimeMillis();
            default:
                return null;
        }
    }

    public static void a() {}
}