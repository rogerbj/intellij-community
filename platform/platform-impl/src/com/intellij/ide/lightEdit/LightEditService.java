// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.lightEdit;

import com.intellij.ide.lightEdit.menuBar.LightEditMenuBar;
import com.intellij.idea.SplashManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationBundle;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.WindowWrapper;
import com.intellij.openapi.ui.WindowWrapperBuilder;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class LightEditService implements Disposable, LightEditorListener {
  private WindowWrapper myWrapper;
  private boolean myWrapperIsStale;
  private final LightEditorManager myEditorManager;

  public static LightEditService getInstance() {
    return ServiceManager.getService(LightEditService.class);
  }

  public LightEditService() {
    myEditorManager = new LightEditorManager();
    myEditorManager.addListener(this);
  }

  private void init() {
    if (myWrapper == null || myWrapperIsStale) {
      final LightEditPanel editorPanel = new LightEditPanel(myEditorManager);
      myWrapper =
        new WindowWrapperBuilder(WindowWrapper.Mode.FRAME, editorPanel)
          .setOnCloseHandler(()-> closeEditorWindow())
          .build();
      setupMenuBar(myWrapper);
      SplashManager.hideBeforeShow(myWrapper.getWindow());
      myWrapperIsStale = false;
    }
  }

  private static void setupMenuBar(@NotNull WindowWrapper wrapper) {
    Window window = wrapper.getWindow();
    if (window instanceof JFrame) {
      ((JFrame)window).setJMenuBar(new LightEditMenuBar());
    }
  }

  public void showEditorWindow() {
    init();
    if (!myWrapper.getWindow().isShowing()) {
      myWrapper.show();
      myWrapper.setTitle(getAppName());
    }
  }

  private static String getAppName() {
    return ApplicationInfo.getInstance().getVersionName();
  }

  public void openFile(@NotNull VirtualFile file) {
    showEditorWindow();
    LightEditorInfo openEditorInfo = myEditorManager.findOpen(file);
    if (openEditorInfo == null) {
      LightEditorInfo newEditorInfo = myEditorManager.createEditor(file);
      if (newEditorInfo != null) {
        getEditPanel().getTabs().addEditorTab(newEditorInfo);
      }
    }
    else {
      getEditPanel().getTabs().selectTab(openEditorInfo);
    }
  }

  public void createNewFile() {
    showEditorWindow();
    LightEditorInfo newEditorInfo = myEditorManager.createEditor();
    getEditPanel().getTabs().addEditorTab(newEditorInfo);
  }

  public boolean closeEditorWindow() {
    if (canClose()) {
      disposeEditorPanel();
      myWrapperIsStale = true;
      Disposer.dispose(myEditorManager);
      if (ProjectManager.getInstance().getOpenProjects().length == 0 && WelcomeFrame.getInstance() == null) {
        Disposer.dispose(myWrapper);
        try {
          ApplicationManager.getApplication().exit();
        }
        catch (Throwable t) {
          System.exit(1);
        }
      }
      return true;
    }
    else {
      return false;
    }
  }

  private boolean canClose() {
    return !myEditorManager.containsUnsavedDocuments() ||
           LightEditUtil.confirmClose(
             ApplicationBundle.message("light.edit.exit.message"),
             ApplicationBundle.message("light.edit.exit.title"),
             () -> FileDocumentManager.getInstance().saveAllDocuments()
           );
  }

  public LightEditPanel getEditPanel() {
    return (LightEditPanel)myWrapper.getComponent();
  }

  private void disposeEditorPanel() {
    LightEditPanel editorPanel = getEditPanel();
    Disposer.dispose(editorPanel);
  }

  @Override
  public void dispose() {
    if (myWrapper != null && !myWrapperIsStale) {
      disposeEditorPanel();
      Disposer.dispose(myWrapper);
      Disposer.dispose(myEditorManager);
    }
  }

  @Override
  public void afterSelect(@Nullable LightEditorInfo editorInfo) {
    if (myWrapper != null && !myWrapperIsStale ) {
      myWrapper.setTitle(getAppName() + (editorInfo != null ? ": " + editorInfo.getFile().getPresentableUrl() : ""));
    }
  }

  @Override
  public void afterClose(@NotNull LightEditorInfo editorInfo) {
    if (myEditorManager.getEditorCount() == 0) {
      closeEditorWindow();
    }
  }

  @NotNull
  public LightEditorManager getEditorManager() {
    return myEditorManager;
  }

  public void saveToAnotherFile(@NotNull Editor editor) {
    LightEditorInfo editorInfo = myEditorManager.getEditorInfo(editor);
    if (editorInfo != null) {
      VirtualFile targetFile = LightEditUtil.chooseTargetFile(myWrapper.getComponent(), editorInfo);
      if (targetFile != null) {
        LightEditorInfo newInfo = myEditorManager.saveAs(editorInfo, targetFile);
        getEditPanel().getTabs().replaceTab(editorInfo, newInfo);
      }
    }
  }
}
