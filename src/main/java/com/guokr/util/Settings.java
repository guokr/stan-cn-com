package com.guokr.util;

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;

public class Settings extends Properties {

    private static final long serialVersionUID = -6306665849100259184L;

    public static Settings    empty            = new Settings(new Properties(), new Properties());

    public static Settings load(String uri) throws Exception {
        Properties props = new Properties();
        try {
            InputStream ins = new URL(uri).openStream();
            props.load(ins);
        } catch (Exception e) {
            throw e;
        }
        return new Settings(props, empty);
    }

    public Settings(Properties currents, Properties defaults) {
        this.defaults = defaults;
        if (currents != null) {
            Enumeration<?> e = currents.propertyNames();
            while (e.hasMoreElements()) {
                String key = e.nextElement().toString();
                String value = currents.getProperty(key);
                this.setProperty(key, value);
            }
        }
    }

}
