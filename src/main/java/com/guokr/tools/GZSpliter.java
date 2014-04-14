package com.guokr.tools;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

import com.guokr.util.MultipleGZIPOutputStream;

public class GZSpliter {

    public static void main(String[] args) {
        String fileIn = args[0];
        String fileOut = args[1];

        InputStream is = null;
        GZIPInputStream gis = null;
        OutputStream os = null;
        try {
            is = new FileInputStream(fileIn);
            gis = new GZIPInputStream(is);
            os = new MultipleGZIPOutputStream(fileOut);

            int byteIn;
            while ((byteIn = gis.read()) != -1) {
                os.write(byteIn);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
                if (gis != null) {
                    gis.close();
                }
                if (os != null) {
                    os.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
