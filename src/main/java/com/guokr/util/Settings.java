package com.guokr.util;

import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

public class Settings extends Properties {

    private static final long serialVersionUID = -6306665849100259184L;

    public static Settings    empty            = new Settings(new Properties(), new Properties());

    public static Settings load(String uri) {
        Properties props = new Properties();
        try {
            System.err.println("before loading settings:" + uri);
            InputStream ins = new URL(uri).openStream();
            props.load(ins);
            System.err.println("after loading settings:" + uri);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return new Settings(props, empty);
    }

    public Settings(Properties currents, Properties defaults) {
        this.defaults = defaults;
    }

}
