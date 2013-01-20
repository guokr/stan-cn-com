package com.guokr.util;

import java.lang.reflect.Method;

public class Reflector {

    public static Class classOf(Object inst) {
        return inst.getClass();
    }

    public static Class classFor(String clz) {
        try {
            return Class.forName(clz);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Object newInstance(Class clz) {
        try {
            return clz.newInstance();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Object newInstance(Class clz, Class[] sign, Object[] args) {
        try {
            return clz.getConstructor(sign).newInstance(args);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Method methodOf(Object inst, String mtd, Class[] sign) {
        return methodFor(classOf(inst), mtd, sign);
    }

    public static Method methodFor(Class clz, String mtd, Class[] sign) {
        try {
            return clz.getDeclaredMethod(mtd, sign);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Method staticMethodOf(Class clz, String mtd, Class[] sign) {
        try {
            return clz.getDeclaredMethod(mtd, sign);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Method staticMethodFor(Class clz, String mtd, Class[] sign) {
        return staticMethodOf(clz, mtd, sign);
    }

    public static Object call(Object inst, String mtd, Class[] sign, Object[] args) {
        try {
            return methodOf(inst, mtd, sign).invoke(inst, args);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void voidCall(Object inst, String mtd, Class[] sign, Object[] args) {
        try {
            methodOf(inst, mtd, sign).invoke(inst, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Object callStaticly(Class clz, String mtd, Class[] sign, Object[] args) {
        try {
            return staticMethodFor(clz, mtd, sign).invoke(null, args);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void voidCallStaticly(Class clz, String mtd, Class[] sign, Object[] args) {
        try {
            staticMethodFor(clz, mtd, sign).invoke(null, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
