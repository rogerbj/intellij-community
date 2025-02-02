// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vfs.local;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ex.PathManagerEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileAttributes;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.io.IoTestUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.impl.jar.BasicJarHandler;
import com.intellij.openapi.vfs.impl.jar.JarFileSystemImpl;
import com.intellij.openapi.vfs.impl.jar.JarHandler;
import com.intellij.openapi.vfs.newvfs.ArchiveFileSystem;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BareTestFixtureTestCase;
import com.intellij.testFramework.rules.TempDirectory;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

import static com.intellij.openapi.util.io.IoTestUtil.assertTimestampsEqual;
import static com.intellij.testFramework.PlatformTestUtil.assertPathsEqual;
import static com.intellij.testFramework.UsefulTestCase.assertOneElement;
import static com.intellij.testFramework.UsefulTestCase.assertSameElements;
import static org.junit.Assert.*;

public class JarFileSystemTest extends BareTestFixtureTestCase {
  private static final Logger LOG = Logger.getInstance(JarFileSystemTest.class);
  @Rule public TempDirectory tempDir = new TempDirectory();

  @Test
  public void testFindFile() throws IOException {
    assertNull(JarFileSystem.getInstance().findFileByPath("/invalid/path"));

    String jarPath = PathManager.getJarPathForClass(Test.class);

    VirtualFile jarRoot = findByPath(jarPath + JarFileSystem.JAR_SEPARATOR);
    assertTrue(jarRoot.isDirectory());

    VirtualFile file2 = findByPath(jarPath + JarFileSystem.JAR_SEPARATOR + "org");
    assertTrue(file2.isDirectory());

    VirtualFile file3 = jarRoot.findChild("org");
    assertEquals(file2, file3);

    VirtualFile file4 = findByPath(jarPath + JarFileSystem.JAR_SEPARATOR + "org/junit/Test.class");
    assertFalse(file4.isDirectory());

    byte[] bytes = file4.contentsToByteArray();
    assertNotNull(bytes);
    assertTrue(bytes.length > 10);
    assertEquals(0xCAFEBABE, ByteBuffer.wrap(bytes).getInt());

    VirtualFile local = ((ArchiveFileSystem)StandardFileSystems.jar()).getLocalByEntry(file4);
    assertNotNull(local);
    assertEquals(local.getTimeStamp(), file4.getTimeStamp());
  }

  @Test
  public void testMetaInf() {
    VirtualFile jarRoot = findByPath(PathManager.getJarPathForClass(Test.class) + JarFileSystem.JAR_SEPARATOR);
    assertTrue(jarRoot.isDirectory());

    VirtualFile metaInf = jarRoot.findChild("META-INF");
    assertNotNull(metaInf);

    assertNotNull(metaInf.findChild("MANIFEST.MF"));
  }

  @Test
  public void testJarRefresh() throws IOException {
    File jar = IoTestUtil.createTestJar(tempDir.newFile("test.jar"));
    assertTrue(jar.setLastModified(jar.lastModified() - 1000));
    VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(jar);
    assertNotNull(vFile);

    VirtualFile jarRoot = findByPath(jar.getPath() + JarFileSystem.JAR_SEPARATOR);
    VirtualFile child = assertOneElement(jarRoot.getChildren());
    assertEquals("META-INF", child.getName());

    VirtualFile entry = findByPath(jar.getPath() + JarFileSystem.JAR_SEPARATOR + JarFile.MANIFEST_NAME);
    assertEquals("", VfsUtilCore.loadText(entry));

    Ref<Boolean> updated = Ref.create(false);
    ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable()).subscribe(
      VirtualFileManager.VFS_CHANGES,
      new BulkFileListener() {
        @Override
        public void before(@NotNull List<? extends VFileEvent> events) {
          for (VFileEvent event : events) {
            if (event instanceof VFileContentChangeEvent && entry.equals(event.getFile())) {
              updated.set(true);
              break;
            }
          }
        }
      }
    );

    IoTestUtil.createTestJar(jar, JarFile.MANIFEST_NAME, "update", "some.txt", "some text");
    vFile.refresh(false, false);

    assertTrue(updated.get());
    assertTrue(entry.isValid());
    assertEquals("update", VfsUtilCore.loadText(entry));
    List<String> children = ContainerUtil.map(jarRoot.getChildren(), f -> f.getName());
    assertEquals(2, children.size());
    assertSameElements(children, "META-INF", "some.txt");

    VirtualFile newEntry = findByPath(jar.getPath() + JarFileSystem.JAR_SEPARATOR + "some.txt");
    assertEquals("some text", VfsUtilCore.loadText(newEntry));
  }

  @Test
  public void testBasicJarHandlerWithInvalidJar() throws Exception {
    final BasicJarHandler handler = new BasicJarHandler("some invalid path");
    Runnable failingIOAction = () -> {
      try {
        handler.getInputStream("").close();
        fail("Unexpected");
      }
      catch (IOException ignored) {
      }
    };
    failingIOAction.run();
    Future<?> future = ApplicationManager.getApplication().executeOnPooledThread(failingIOAction);
    try {
      future.get(1, TimeUnit.SECONDS);
    }
    catch (TimeoutException exception) {
      fail("Deadlock detected");
    }
  }

  @Test
  public void testBasicJarHandlerConcurrency() throws Exception {
    try {
      int number = 40;
      List<BasicJarHandler> handlers = new ArrayList<>();
      for (int i = 0; i < number; ++i) {
        File jar = IoTestUtil.createTestJar(tempDir.newFile("test" + i + ".jar"));
        handlers.add(new BasicJarHandler(jar.getPath()));
      }

      int N = Math.max(2, Runtime.getRuntime().availableProcessors());
      for (int iteration = 0; iteration < 200; ++iteration) {
        List<Future> futuresToWait = new ArrayList<>();
        CountDownLatch sameStartCondition = new CountDownLatch(N);

        for (int i = 0; i < N; ++i) {
          futuresToWait.add(ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
              sameStartCondition.countDown();
              sameStartCondition.await();
              Random random = new Random();
              for (int j = 0; j < 2 * number; ++j) {
                BasicJarHandler handler = handlers.get(random.nextInt(handlers.size()));
                if (random.nextBoolean()) {
                  assertNotNull(handler.getAttributes(JarFile.MANIFEST_NAME));
                }
                else {
                  assertNotNull(handler.contentsToByteArray(JarFile.MANIFEST_NAME));
                }
              }
            }
            catch (Throwable t) {
              t.printStackTrace();
            }
          }));
        }

        for (Future future : futuresToWait) future.get(2, TimeUnit.SECONDS);
      }
    }
    catch (TimeoutException e) {
      fail("Deadlock detected");
    }
  }

  @After
  public void testDown() {
    JarFileSystemImpl.cleanupForNextTest();
  }

  @Test
  public void testJarHandlerDoNotCreateCopyWhenListingArchive() throws Exception {
    File jar = IoTestUtil.createTestJar(tempDir.newFile("test.jar"));
    JarHandler handler = new JarHandler(jar.getPath());
    FileAttributes attributes = handler.getAttributes(JarFile.MANIFEST_NAME);
    assertNotNull(attributes);
    assertEquals(0, attributes.length);
    assertTimestampsEqual(jar.lastModified(), attributes.lastModified);

    JarFileSystemImpl jarFileSystem = (JarFileSystemImpl)JarFileSystem.getInstance();
    if (jarFileSystem.isMakeCopyOfJar(jar)) {
      // for performance reasons we create file copy on windows when we read contents and have the handle open to the copy
      Field resolved = handler.getClass().getDeclaredField("myFileWithMirrorResolved");
      resolved.setAccessible(true);
      assertNull(resolved.get(handler));
    }

    jarFileSystem.setNoCopyJarForPath(jar.getPath() + JarFileSystem.JAR_SEPARATOR);
    assertFalse(jarFileSystem.isMakeCopyOfJar(jar));
  }

  @Test
  public void testInvalidJar() {
    String jarPath = PathManagerEx.getTestDataPath() + "/vfs/maven-toolchain-1.0.jar";
    VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(jarPath);
    assertNotNull(vFile);
    VirtualFile manifest = findByPath(jarPath + JarFileSystem.JAR_SEPARATOR + JarFile.MANIFEST_NAME);
    assertNotNull(manifest);
    VirtualFile classFile = findByPath(jarPath + JarFileSystem.JAR_SEPARATOR + "org/apache/maven/toolchain/java/JavaToolChain.class");
    assertNotNull(classFile);
  }

  @Test
  public void testCrazyBackSlashesInZipEntriesMustBeTreatedAsRegularDirectorySeparators() {
    String jarPath = PathManagerEx.getTestDataPath() + "/vfs/log4sql.jar";
    VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(jarPath);
    assertNotNull(vFile);
    VirtualFile manifest = findByPath(jarPath + JarFileSystem.JAR_SEPARATOR + JarFile.MANIFEST_NAME);
    assertNotNull(manifest);

    VirtualFile jarRoot = JarFileSystem.getInstance().findFileByPath(jarPath + JarFileSystem.JAR_SEPARATOR);
    assertNotNull(jarRoot);
    String crazyDir = "src\\core\\log";
    String crazyEntry = "/log4sql_conf.jsp";
    if (SystemInfo.isWindows) {
      assertNotNull(findByPath(jarPath + JarFileSystem.JAR_SEPARATOR + crazyDir.replace('\\', '/') + crazyEntry));
      assertNull(jarRoot.findChild(crazyDir));
    }
    else {
      assertNull(JarFileSystem.getInstance().findFileByPath(jarPath + JarFileSystem.JAR_SEPARATOR + crazyDir.replace('\\', '/') + crazyEntry));
      VirtualFile dir = jarRoot.findChild(crazyDir);
      LOG.debug(jarRoot + " children: " + Arrays.toString(jarRoot.getChildren()));
      LOG.debug(" exist child: " + ContainerUtil.exists(jarRoot.getChildren(), c->c.getName().equals(crazyDir)));
      LOG.debug(" persist children: " + Arrays.toString(PersistentFS.getInstance().listAll(jarRoot)));

      try (ZipFile file = new ZipFile(jarPath)) {
        LOG.debug("Entries: " + ContainerUtil.toList(file.entries()));
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
      assertNotNull(dir);
      assertNotNull(dir.findChild(crazyEntry));
    }
  }

  @Test
  public void testJarRootForLocalFile() {
    String jarPath = PathManager.getJarPathForClass(Test.class);

    VirtualFile jarFile = LocalFileSystem.getInstance().findFileByPath(jarPath);
    assertNotNull(jarFile);
    VirtualFile jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(jarFile);
    assertNotNull(jarRoot);

    VirtualFile entryFile = findByPath(jarPath + JarFileSystem.JAR_SEPARATOR + "org/junit/Test.class");
    VirtualFile entryRoot = JarFileSystem.getInstance().getJarRootForLocalFile(entryFile);
    assertNull(entryRoot);

    VirtualFile nonJarFile = LocalFileSystem.getInstance().findFileByPath(PlatformTestUtil.getJavaExe());
    assertNotNull(nonJarFile);
    VirtualFile nonJarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(nonJarFile);
    assertNull(nonJarRoot);
  }

  @Test
  public void testEnormousFileInputStream() throws IOException {
    File root = tempDir.newFolder("out");
    FileUtil.writeToFile(new File(root, "small1"), "some text");
    FileUtil.writeToFile(new File(root, "small2"), "another text");
    try (InputStream is = new ZeroInputStream(); OutputStream os = new FileOutputStream(new File(root, "large"))) {
      FileUtil.copy(is, FileUtilRt.LARGE_FOR_CONTENT_LOADING * 2, os);
    }
    File jar = IoTestUtil.createTestJar(tempDir.newFile("test.jar"), root);

    VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(jar);
    assertNotNull(vFile);
    VirtualFile jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(vFile);
    assertNotNull(jarRoot);

    VirtualFile small1 = jarRoot.findChild("small1");
    VirtualFile small2 = jarRoot.findChild("small2");
    VirtualFile large = jarRoot.findChild("large");
    try (InputStream is1 = small1.getInputStream();
         InputStream is2 = small2.getInputStream();
         InputStream il = large.getInputStream()) {
      assertSame(is1.getClass(), is2.getClass());
      assertNotSame(is1.getClass(), il.getClass());
    }
  }

  @NotNull
  private static VirtualFile findByPath(String path) {
    VirtualFile file = JarFileSystem.getInstance().findFileByPath(path);
    assertNotNull(file);
    assertPathsEqual(path, file.getPath());
    return file;
  }

  private static class ZeroInputStream extends InputStream {
    @Override
    public int read() {
      return 0;
    }

    @Override
    public int read(@NotNull byte[] b, int off, int len) {
      return len;
    }
  }
}