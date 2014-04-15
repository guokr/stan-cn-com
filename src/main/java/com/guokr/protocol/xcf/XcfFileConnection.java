package com.guokr.protocol.xcf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

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
        String name = abspath.substring(parpath.length()).substring(1);

        StringBuilder regex = new StringBuilder("^");
        regex.append(name);
        if (pattern != null) {
            regex.append("\\.part.");
            for (int i = 0; i < pattern.length(); i++) {
                regex.append("\\d");
            }
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
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File o1, File o2) {
                    String path1 = o1.getAbsolutePath();
                    String path2 = o2.getAbsolutePath();
                    return path1.compareTo(path2);
                }
            });
            if (files.length == 1) {
                inputs.add(new FileInputStream(files[0]));
            } else {
                for (int i = 0; i < files.length; i++) {
                    inputs.add(new GZIPInputStream(new FileInputStream(files[i])));
                }
            }
        }

        System.out.println(base);
        System.out.println(find);
        System.out.println(path);
        System.out.println(abspath);
        System.out.println(parpath);
        System.out.println(name);
        System.out.println(regex);
        System.out.println(files.length);
        System.out.println(inputs.size());

        return inputs;
    }

}
