// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.wm.impl;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.*;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import com.intellij.ui.content.*;
import com.intellij.ui.content.impl.ContentImpl;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.EventDispatcher;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkListener;
import java.awt.*;
import java.awt.event.InputEvent;
import java.beans.PropertyChangeListener;
import java.util.List;
import java.util.*;

public final class ToolWindowHeadlessManagerImpl extends ToolWindowManagerEx {
  private final Map<String, ToolWindow> myToolWindows = new HashMap<>();
  private final Project myProject;

  public ToolWindowHeadlessManagerImpl(Project project) {
    myProject = project;
  }

  @Override
  public boolean canShowNotification(@NotNull String toolWindowId) {
    return false;
  }

  @Override
  public void notifyByBalloon(@NotNull String toolWindowId, @NotNull MessageType type, @NotNull String htmlBody) {
  }

  @NotNull
  public ToolWindow doRegisterToolWindow(@NotNull String id) {
    MockToolWindow toolWindow = new MockToolWindow(myProject);
    myToolWindows.put(id, toolWindow);
    return toolWindow;
  }

  @NotNull
  @Override
  protected ToolWindow registerToolWindow(@NotNull RegisterToolWindowTask task) {
    return doRegisterToolWindow(task.getId());
  }

  @Override
  public void unregisterToolWindow(@NotNull String id) {
    ToolWindow toolWindow = myToolWindows.remove(id);
    if (toolWindow != null) {
      Disposer.dispose(((MockToolWindow)toolWindow).myContentManager);
    }
  }

  @Override
  public void activateEditorComponent() {
  }

  @Override
  public boolean isEditorComponentActive() {
    return false;
  }

  @NotNull
  @Override
  public String[] getToolWindowIds() {
    return ArrayUtilRt.EMPTY_STRING_ARRAY;
  }

  @Override
  public String getActiveToolWindowId() {
    return null;
  }

  @Override
  public ToolWindow getToolWindow(@Nullable String id) {
    return myToolWindows.get(id);
  }

  @Override
  public void invokeLater(@NotNull Runnable runnable) {
  }

  @NotNull
  @Override
  public IdeFocusManager getFocusManager() {
    return IdeFocusManagerHeadless.INSTANCE;
  }

  @Override
  public void notifyByBalloon(@NotNull final String toolWindowId,
                              @NotNull final MessageType type,
                              @NotNull final String text,
                              @Nullable final Icon icon,
                              @Nullable final HyperlinkListener listener) {
  }

  @Override
  public Balloon getToolWindowBalloon(@NotNull String id) {
    return null;
  }

  @Override
  public boolean isMaximized(@NotNull ToolWindow wnd) {
    return false;
  }

  @Override
  public void setMaximized(@NotNull ToolWindow window, boolean maximized) {
  }

  @Override
  public void initToolWindow(@NotNull ToolWindowEP bean) {
  }

  @Override
  public boolean fallbackToEditor() {
    return false;
  }

  @Override
  public String getLastActiveToolWindowId() {
    return null;
  }

  @Override
  public String getLastActiveToolWindowId(Condition<? super JComponent> condition) {
    return null;
  }

  @NotNull
  @Override
  public DesktopLayout getLayout() {
    return new DesktopLayout();
  }

  @Override
  public void setLayoutToRestoreLater(DesktopLayout layout) {
  }

  @Override
  public DesktopLayout getLayoutToRestoreLater() {
    return new DesktopLayout();
  }

  @Override
  public void setLayout(@NotNull DesktopLayout layout) {
  }

  @Override
  public void clearSideStack() {
  }

  @Override
  public void hideToolWindow(@NotNull final String id, final boolean hideSide) {
  }

  @NotNull
  @Override
  public List<String> getIdsOn(@NotNull final ToolWindowAnchor anchor) {
    return new ArrayList<>();
  }

  @NotNull
  @Override
  public Icon getLocationIcon(@NotNull String id, @NotNull Icon fallbackIcon) {
    return fallbackIcon;
  }

  public static class MockToolWindow implements ToolWindowEx {
    final ContentManager myContentManager = new MockContentManager();

    public MockToolWindow(@NotNull Project project) {
      Disposer.register(project, myContentManager);
    }

    @Override
    public boolean isActive() {
      return false;
    }

    @Override
    public void activate(@Nullable Runnable runnable) {
    }

    @Override
    public boolean isDisposed() {
      return false;
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void setShowStripeButton(boolean show) {
    }

    @Override
    public boolean isShowStripeButton() {
      return false;
    }

    @NotNull
    @Override
    public ActionCallback getReady(@NotNull Object requestor) {
      return ActionCallback.DONE;
    }

    @Override
    public void show(@Nullable Runnable runnable) {
    }

    @Override
    public void hide(@Nullable Runnable runnable) {
    }

    @Override
    public ToolWindowAnchor getAnchor() {
      return ToolWindowAnchor.BOTTOM;
    }

    @Override
    public void setAnchor(@NotNull ToolWindowAnchor anchor, @Nullable Runnable runnable) {
    }

    @Override
    public boolean isSplitMode() {
      return false;
    }

    @Override
    public void setSplitMode(final boolean isSideTool, @Nullable final Runnable runnable) {

    }

    @Override
    public boolean isAutoHide() {
      return false;
    }

    @Override
    public void setAutoHide(boolean state) {
    }

    @Override
    public void setToHideOnEmptyContent(final boolean hideOnEmpty) {
    }

    @Override
    public boolean isToHideOnEmptyContent() {
      return false;
    }

    @Override
    public ToolWindowType getType() {
      return ToolWindowType.SLIDING;
    }

    @Override
    public void setType(@NotNull ToolWindowType type, @Nullable Runnable runnable) {
    }

    @Override
    public Icon getIcon() {
      return null;
    }

    @Override
    public void setIcon(@NotNull Icon icon) {
    }

    @Override
    public String getTitle() {
      return "";
    }

    @Override
    public void setTitle(String title) {
    }

    @NotNull
    @Override
    public String getStripeTitle() {
      return "";
    }

    @Override
    public void setStripeTitle(@NotNull String title) {
    }

    @Override
    public boolean isAvailable() {
      return false;
    }

    @Override
    public void setContentUiType(@NotNull ToolWindowContentUiType type, @Nullable Runnable runnable) {
    }

    @Override
    public void setDefaultContentUiType(@NotNull ToolWindowContentUiType type) {
    }

    @NotNull
    @Override
    public ToolWindowContentUiType getContentUiType() {
      return ToolWindowContentUiType.TABBED;
    }

    @Override
    public void setAvailable(boolean available, @Nullable Runnable runnable) {
    }

    @Override
    public void installWatcher(ContentManager contentManager) {
    }

    @Override
    public JComponent getComponent() {
      return new JLabel();
    }

    @Override
    public ContentManager getContentManager() {
      return myContentManager;
    }

    @Override
    public void setDefaultState(@Nullable final ToolWindowAnchor anchor,
                                @Nullable final ToolWindowType type,
                                @Nullable final Rectangle floatingBounds) {
    }

    @Override
    public void activate(@Nullable final Runnable runnable, final boolean autoFocusContents) {
    }

    @Override
    public void activate(@Nullable Runnable runnable, boolean autoFocusContents, boolean forced) {
    }

    @Override
    public void showContentPopup(InputEvent inputEvent) {
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener l) {
    }

    @Override
    public ToolWindowType getInternalType() {
      return ToolWindowType.DOCKED;
    }

    @Override
    public void stretchWidth(int value) {
    }

    @Override
    public void stretchHeight(int value) {
    }

    @Override
    public InternalDecorator getDecorator() {
      return null;
    }

    @Override
    public void setAdditionalGearActions(ActionGroup additionalGearActions) {
    }

    @Override
    public void setTitleActions(AnAction... actions) {
    }

    @Override
    public void setTabActions(AnAction... actions) {
    }

    @Override
    public void setUseLastFocusedOnActivation(boolean focus) {
    }

    @Override
    public boolean isUseLastFocusedOnActivation() {
      return false;
    }
  }

  private static class MockContentManager implements ContentManager {
    private final EventDispatcher<ContentManagerListener> myDispatcher = EventDispatcher.create(ContentManagerListener.class);
    private final List<Content> myContents = new ArrayList<>();
    private Content mySelected;

    @NotNull
    @Override
    public ActionCallback getReady(@NotNull Object requestor) {
      return ActionCallback.DONE;
    }

    @Override
    public void addContent(@NotNull final Content content) {
      addContent(content, -1);
    }

    @Override
    public void addContent(@NotNull Content content, int order) {
      myContents.add(order == -1 ? myContents.size() : order, content);
      if (content instanceof ContentImpl && content.getManager() == null) {
        ((ContentImpl)content).setManager(this);
      }
      Disposer.register(this, content);
      ContentManagerEvent e = new ContentManagerEvent(this, content, myContents.indexOf(content), ContentManagerEvent.ContentOperation.add);
      myDispatcher.getMulticaster().contentAdded(e);
      if (mySelected == null) setSelectedContent(content);
    }

    @Override
    public void addSelectedContent(@NotNull final Content content) {
      addContent(content);
      setSelectedContent(content);
    }

    @Override
    public void addContentManagerListener(@NotNull final ContentManagerListener l) {
      myDispatcher.getListeners().add(0, l);
    }

    @Override
    public void addDataProvider(@NotNull final DataProvider provider) {
    }

    @Override
    public boolean canCloseAllContents() {
      return false;
    }

    @Override
    public boolean canCloseContents() {
      return false;
    }

    @Override
    public Content findContent(final String displayName) {
      for (Content each : myContents) {
        if (each.getDisplayName().equals(displayName)) return each;
      }
      return null;
    }

    @NotNull
    @Override
    public List<AnAction> getAdditionalPopupActions(@NotNull final Content content) {
      return Collections.emptyList();
    }

    @NotNull
    @Override
    public String getCloseActionName() {
      return "close";
    }

    @NotNull
    @Override
    public String getCloseAllButThisActionName() {
      return "closeallbutthis";
    }

    @NotNull
    @Override
    public String getPreviousContentActionName() {
      return "previous";
    }

    @NotNull
    @Override
    public String getNextContentActionName() {
      return "next";
    }

    @NotNull
    @Override
    public JComponent getComponent() {
      return new JLabel();
    }

    @Override
    public Content getContent(@NotNull final JComponent component) {
      Content[] contents = getContents();
      for (Content content : contents) {
        if (Comparing.equal(component, content.getComponent())) {
          return content;
        }
      }
      return null;
    }

    @Override
    @Nullable
    public Content getContent(final int index) {
      return myContents.get(index);
    }

    @Override
    public int getContentCount() {
      return myContents.size();
    }

    @Override
    @NotNull
    public Content[] getContents() {
      return myContents.toArray(new Content[0]);
    }

    @Override
    public int getIndexOfContent(@NotNull final Content content) {
      return myContents.indexOf(content);
    }

    @Override
    @Nullable
    public Content getSelectedContent() {
      return mySelected;
    }

    @Override
    @NotNull
    public Content[] getSelectedContents() {
      return mySelected != null ? new Content[]{mySelected} : new Content[0];
    }

    @Override
    public boolean isSelected(@NotNull final Content content) {
      return content == mySelected;
    }

    @Override
    public void removeAllContents(final boolean dispose) {
      for (Content content : getContents()) {
        removeContent(content, dispose);
      }
    }

    @Override
    public boolean removeContent(@NotNull final Content content, final boolean dispose) {
      boolean wasSelected = mySelected == content;
      int oldIndex = myContents.indexOf(content);
      if (wasSelected) {
        removeFromSelection(content);
      }
      boolean result = myContents.remove(content);
      if (dispose) Disposer.dispose(content);
      ContentManagerEvent e = new ContentManagerEvent(this, content, oldIndex, ContentManagerEvent.ContentOperation.remove);
      myDispatcher.getMulticaster().contentRemoved(e);
      Content item = ContainerUtil.getFirstItem(myContents);
      if (item != null) {
        setSelectedContent(item);
      }
      else {
        mySelected = null;
      }
      return result;
    }

    @NotNull
    @Override
    public ActionCallback removeContent(@NotNull Content content, boolean dispose, boolean requestFocus, boolean implicitFocus) {
      removeContent(content, dispose);
      return ActionCallback.DONE;
    }

    @Override
    public void removeContentManagerListener(@NotNull final ContentManagerListener l) {
      myDispatcher.removeListener(l);
    }

    @Override
    public void removeFromSelection(@NotNull final Content content) {
      ContentManagerEvent e = new ContentManagerEvent(this, content, myContents.indexOf(mySelected), ContentManagerEvent.ContentOperation.remove);
      myDispatcher.getMulticaster().selectionChanged(e);
    }

    @Override
    public ActionCallback selectNextContent() {
      return ActionCallback.DONE;
    }

    @Override
    public ActionCallback selectPreviousContent() {
      return ActionCallback.DONE;
    }

    @Override
    public void setSelectedContent(@NotNull final Content content) {
      if (mySelected != null) {
        removeFromSelection(mySelected);
      }
      mySelected = content;
      ContentManagerEvent e = new ContentManagerEvent(this, content, myContents.indexOf(content), ContentManagerEvent.ContentOperation.add);
      myDispatcher.getMulticaster().selectionChanged(e);
    }

    @NotNull
    @Override
    public ActionCallback setSelectedContentCB(@NotNull Content content) {
      setSelectedContent(content);
      return ActionCallback.DONE;
    }

    @Override
    public void setSelectedContent(@NotNull final Content content, final boolean requestFocus) {
      setSelectedContent(content);
    }

    @NotNull
    @Override
    public ActionCallback setSelectedContentCB(@NotNull final Content content, final boolean requestFocus) {
      return setSelectedContentCB(content);
    }

    @Override
    public void setSelectedContent(@NotNull Content content, boolean requestFocus, boolean forcedFocus) {
      setSelectedContent(content);
    }

    @NotNull
    @Override
    public ActionCallback setSelectedContentCB(@NotNull final Content content, final boolean requestFocus, final boolean forcedFocus) {
      return setSelectedContentCB(content);
    }

    @NotNull
    @Override
    public ActionCallback setSelectedContent(@NotNull Content content, boolean requestFocus, boolean forcedFocus, boolean implicit) {
      return setSelectedContentCB(content);
    }

    @NotNull
    @Override
    public ActionCallback requestFocus(@Nullable final Content content, final boolean forced) {
      return ActionCallback.DONE;
    }

    @Override
    public void dispose() {
      myContents.clear();
      mySelected = null;
      myDispatcher.getListeners().clear();
    }

    @Override
    public boolean isDisposed() {
      return false;
    }

    @Override
    public boolean isSingleSelection() {
      return true;
    }

    @Override
    @NotNull
    public ContentFactory getFactory() {
      return ServiceManager.getService(ContentFactory.class);
    }
  }}
