package com.guokr.protocol;

public enum Protocols {
    INSTANCE;

    Protocols() {
        try {
            String pkgs = System.getProperty("java.protocol.handler.pkgs");
            if (pkgs != null) {
                pkgs = pkgs + "|com.guokr.protocol";
            } else {
                pkgs = "com.guokr.protocol";
            }
            System.setProperty("java.protocol.handler.pkgs", pkgs);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

}
