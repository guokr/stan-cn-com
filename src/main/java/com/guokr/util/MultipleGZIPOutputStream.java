package com.guokr.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

public class MultipleGZIPOutputStream extends OutputStream {

    private final String     baseFileName;
    private final long       bufferSize = 13107200;
    private long             bytesRead;
    private int              part;
    private String           currentFileName;
    private GZIPOutputStream gos;

    public MultipleGZIPOutputStream(String fileName) {
        super();
        this.bytesRead = 0;
        this.part = 0;
        this.baseFileName = fileName;
        updateCurrentFileName();
    }

    private void updateCurrentFileName() {
        this.part += 1;
        this.currentFileName = this.baseFileName + ".part." + String.format("%02d", this.part);
    }

    private GZIPOutputStream getGZIPOutputStream() throws IOException {
        if (this.bytesRead % this.bufferSize == 0) {
            if (this.gos != null) {
                this.gos.flush();
                this.gos.close();
                updateCurrentFileName();
            }
            this.gos = new GZIPOutputStream(new FileOutputStream(this.currentFileName));
        }
        this.bytesRead += 1;
        return this.gos;
    }

    public void write(int b) throws IOException {
        getGZIPOutputStream().write(b);
    }

    @Override
    public void flush() throws IOException {
        if (this.gos != null) {
            this.gos.flush();
        }
    }

    @Override
    public void close() throws IOException {
        if (this.gos != null) {
            this.gos.close();
        }
        super.close();
    }
}