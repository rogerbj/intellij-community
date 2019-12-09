// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.indexing.impl.perFileVersion;

import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWithId;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.CompositeDataIndexer;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public class PersistentSubIndexerVersionEnumeratorTest extends LightJavaCodeInsightFixtureTestCase {
  private TempDirTestFixture myDirTestFixture;
  private File myRoot;

  private PersistentSubIndexerRetriever<MyIndexFileAttribute, String> myMap;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myRoot = FileUtil.createTempDirectory("persistent", "map");
    myDirTestFixture = new TempDirTestFixtureImpl();
    myDirTestFixture.setUp();
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      myMap.close();
      myDirTestFixture.tearDown();
    }
    catch (Exception e) {
      addSuppressedException(e);
    } finally {
      super.tearDown();
    }
  }

  public void testAddRetrieve() throws IOException {
    myMap = new PersistentSubIndexerRetriever<>(myRoot, "index_name", 0, new MyPerFileIndexExtension(foo1, boo1, bar1, baz1));
    persistIndexedState(foo1);
    persistIndexedState(boo1);

    assertTrue(isIndexedState(foo1));
    assertTrue(isIndexedState(boo1));

    assertFalse(isIndexedState(foo2));
    assertFalse(isIndexedState(baz1));
  }

  public void testInvalidation() throws IOException {
    myMap = new PersistentSubIndexerRetriever<>(myRoot, "index_name", 0, new MyPerFileIndexExtension(foo1));
    persistIndexedState(foo1);
    assertTrue(isIndexedState(foo1));
    myMap.close();
    myMap = new PersistentSubIndexerRetriever<>(myRoot, "index_name", 0, new MyPerFileIndexExtension(foo2));
    assertFalse(isIndexedState(foo2));
    persistIndexedState(foo2);
    assertTrue(isIndexedState(foo2));
    assertFalse(isIndexedState(foo1));
  }

  public void testStaleKeysRemoval() throws IOException {
    int baseThreshold = PersistentSubIndexerVersionEnumerator.getCompactThreshold();
    PersistentSubIndexerVersionEnumerator.setCompactThreshold(2);
    try {
      myMap = new PersistentSubIndexerRetriever<>(myRoot, "index_name", 0, new MyPerFileIndexExtension(foo1, boo1, bar1, baz1));
      persistIndexedState(foo1);
      persistIndexedState(boo1);
      persistIndexedState(bar1);
      persistIndexedState(baz1);

      assertTrue(isIndexedState(foo1));
      assertTrue(isIndexedState(boo1));
      assertTrue(isIndexedState(bar1));
      assertTrue(isIndexedState(baz1));

      myMap.close();
      myMap = new PersistentSubIndexerRetriever<>(myRoot, "index_name", 0, new MyPerFileIndexExtension(foo1, boo1));

      assertTrue(isIndexedState(foo1));
      assertTrue(isIndexedState(boo1));
      assertFalse(isIndexedState(bar1));
      assertFalse(isIndexedState(baz1));

    } finally {
      PersistentSubIndexerVersionEnumerator.setCompactThreshold(baseThreshold);
    }

  }

  private static final Key<MyIndexFileAttribute> ATTRIBUTE_KEY = Key.create("my.index.attr.key");
  private static class MyPerFileIndexExtension implements CompositeDataIndexer<String, String, MyIndexFileAttribute, String> {
    private final Set<MyIndexFileAttribute> myAllAvailableAttributes;

    private MyPerFileIndexExtension(MyIndexFileAttribute... attributes) {myAllAvailableAttributes = ContainerUtil.newHashSet(attributes);}

    @NotNull
    @Override
    public Collection<MyIndexFileAttribute> getAllAvailableSubIndexers() {
      return myAllAvailableAttributes;
    }

    @Nullable
    @Override
    public MyIndexFileAttribute calculateSubIndexer(@NotNull VirtualFile content) {
      return content.getUserData(ATTRIBUTE_KEY);
    }

    @Override
    public boolean requiresContentForSubIndexerEvaluation(@NotNull VirtualFile content) {
      return false;
    }

    @NotNull
    @Override
    public String getSubIndexerVersion(@NotNull MyIndexFileAttribute attribute) {
      return attribute.name + ":" + attribute.version;
    }

    @NotNull
    @Override
    public KeyDescriptor<String> getSubIndexerVersionDescriptor() {
      return EnumeratorStringDescriptor.INSTANCE;
    }

    @NotNull
    @Override
    public Map<String, String> map(@NotNull FileContent inputData, @NotNull MyIndexFileAttribute attribute) {
      return null;
    }
  }

  private final MyIndexFileAttribute foo1 = new MyIndexFileAttribute(1, "foo");
  private final MyIndexFileAttribute foo2 = new MyIndexFileAttribute(2, "foo");
  private final MyIndexFileAttribute foo3 = new MyIndexFileAttribute(3, "foo");

  private final MyIndexFileAttribute bar1 = new MyIndexFileAttribute(1, "bar");
  private final MyIndexFileAttribute bar2 = new MyIndexFileAttribute(2, "bar");

  private final MyIndexFileAttribute baz1 = new MyIndexFileAttribute(1, "baz");

  private final MyIndexFileAttribute boo1 = new MyIndexFileAttribute(1, "boo");

  private static class MyIndexFileAttribute {
    final int version;
    final String name;

    private MyIndexFileAttribute(int version, String name) {
      this.version = version;
      this.name = name;
    }
  }

  @NotNull
  VirtualFile file(@NotNull MyIndexFileAttribute attribute) {
    return myDirTestFixture.createFile(attribute.name + ".java");
  }

  void persistIndexedState(@NotNull MyIndexFileAttribute attribute) {
    VirtualFile file = file(attribute);
    file.putUserData(ATTRIBUTE_KEY, attribute);
    try {
      myMap.persistIndexedState(((VirtualFileWithId)file).getId(), file);
    }
    catch (IOException e) {
      LOG.error(e);
      fail(e.getMessage());
    } finally {
      file.putUserData(ATTRIBUTE_KEY, null);
    }
  }

  boolean isIndexedState(@NotNull MyIndexFileAttribute attribute) {
    VirtualFile file = file(attribute);
    file.putUserData(ATTRIBUTE_KEY, attribute);
    try {
      return myMap.isIndexed(((VirtualFileWithId)file).getId(), file);
    }
    catch (IOException e) {
      LOG.error(e);
      fail(e.getMessage());
      return false;
    } finally {
      file.putUserData(ATTRIBUTE_KEY, null);
    }
  }
}

