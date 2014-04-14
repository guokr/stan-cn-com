package com.guokr.protocol.xcf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class XcfFileConnection extends XcfConnection {

    public XcfFileConnection(URL base, URL url) {
        super(base, url);
    }

    @Override
    public List<InputStream> list(URL base, String find, String pattern) throws IOException {
        String basepath = base.getPath();

        String path = basepath + find;
        String abspath = new File(path).getAbsolutePath();
        String parpath = new File(abspath).getParent();
        String name = abspath.substring(parpath.length());

        StringBuilder regex = new StringBuilder(name);
        regex.append("\\.part.");
        for (int i = 0; i < pattern.length(); i++) {
            regex.append("\\d");
        }
        regex.append("$");
        final Pattern pat = Pattern.compile(regex.toString());

        File[] files = new File(parpath).listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return pat.matcher(name).find();
            }
        });

        List<InputStream> inputs = new ArrayList<InputStream>();
        for (int i = 0; i < files.length; i++) {
            inputs.add(new FileInputStream(files[i]));
        }

        return inputs;
    }

}
