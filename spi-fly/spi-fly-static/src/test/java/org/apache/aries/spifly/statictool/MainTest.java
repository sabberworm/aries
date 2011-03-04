/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.spifly.statictool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.apache.aries.spifly.Streams;
import org.junit.Assert;
import org.junit.Test;

public class MainTest {
    @Test
    public void testUnJar() throws Exception {
        URL jarURL = getClass().getResource("/testjar.jar");
        File jarFile = new File(jarURL.getFile());
        File tempDir = new File(System.getProperty("java.io.tmpdir") + "/testjar_" + System.currentTimeMillis());
        
        try {
            Main.unJar(jarFile, tempDir);
            
            assertStreams(new File(tempDir, "META-INF/MANIFEST.MF"), 
                    "jar:" + jarURL + "!/META-INF/MANIFEST.MF");
            
            assertStreams(new File(tempDir, "A text File with no content"),
                    "jar:" + jarURL + "!/A text File with no content");
            assertStreams(new File(tempDir, "dir/Main.class"),
                    "jar:" + jarURL + "!/dir/Main.class");
            assertStreams(new File(tempDir, "dir/dir 2/a.txt"), 
                    "jar:" + jarURL + "!/dir/dir 2/a.txt");
            assertStreams(new File(tempDir, "dir/dir 2/b.txt"), 
                    "jar:" + jarURL + "!/dir/dir 2/b.txt");
                        
            Assert.assertTrue(new File(tempDir, "dir/dir.3").exists());
        } finally {
            deleteTree(tempDir);
        }
    }

    private void assertStreams(File file, String url) throws Exception {
        InputStream is1 = new FileInputStream(file);
        InputStream is2 = new URL(url).openStream();
        try {
            byte[] bytes1 = Streams.suck(is1);
            byte[] bytes2 = Streams.suck(is2);
            Assert.assertArrayEquals("Files not equal", bytes1, bytes2);
        } finally {
            is1.close();
            is2.close();
        }
    }

    public static void deleteTree(File dir) throws IOException {
        if (!dir.isDirectory()) 
            return;
                
        for (File f : new DirTree(dir).getFiles()) {
            if (!f.delete()) {
                throw new IOException("Unable to delete file " + f.getAbsolutePath() +
                    " The file may still be in use.");
            }
        }
    }
}


