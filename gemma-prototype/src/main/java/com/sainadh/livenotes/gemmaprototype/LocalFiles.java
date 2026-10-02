package com.sainadh.livenotes.gemmaprototype;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Serializes Activity/service snapshots; a killed process cannot leave a partial source file. */
final class LocalFiles {
    private LocalFiles() { }
    static synchronized String read(File file) throws IOException {
        if (!file.isFile() && !new File(file.getPath()+".bak").isFile()) return "";
        return new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8);
    }
    static synchronized void write(File file, String text) throws IOException {
        AtomicFile target = new AtomicFile(file);
        FileOutputStream stream = null;
        try {
            stream = target.startWrite();
            stream.write(text.getBytes(StandardCharsets.UTF_8));
            target.finishWrite(stream);
        } catch (IOException failure) {
            if (stream != null) target.failWrite(stream);
            throw failure;
        }
    }
    static synchronized void delete(File file) { new AtomicFile(file).delete(); }
}
