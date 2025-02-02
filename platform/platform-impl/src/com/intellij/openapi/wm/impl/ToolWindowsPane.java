// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.wm.impl;

import com.intellij.ide.RemoteDesktopService;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.ui.UISettingsListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.ui.ThreeComponentsSplitter;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryValue;
import com.intellij.openapi.util.registry.RegistryValueListener;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowType;
import com.intellij.openapi.wm.impl.commands.FinalizableCommand;
import com.intellij.reference.SoftReference;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLayeredPane;
import com.intellij.ui.paint.PaintUtil;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.ui.scale.ScaleContext;
import com.intellij.util.IJSwingUtilities;
import com.intellij.util.ui.ImageUtil;
import gnu.trove.TObjectFloatHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static com.intellij.util.ui.UIUtil.useSafely;

/**
 * This panel contains all tool stripes and JLayeredPane at the center area. All tool windows are
 * located inside this layered pane.
 *
 * @author Anton Katilin
 * @author Vladimir Kondratyev
 */
public final class ToolWindowsPane extends JBLayeredPane implements UISettingsListener {
  private static final Logger LOG = Logger.getInstance(ToolWindowsPane.class);
  public static final String TEMPORARY_ADDED = "TEMPORARY_ADDED";

  private final JFrame myFrame;

  private final Map<String, StripeButton> myIdToButton = new HashMap<>();
  private final Map<InternalDecorator, WindowInfoImpl> myDecoratorToInfo = new HashMap<>();
  private final TObjectFloatHashMap<String> myIdToSplitProportion = new TObjectFloatHashMap<>();
  private Pair<ToolWindow, Integer> myMaximizedProportion;
  /**
   * This panel is the layered pane where all sliding tool windows are located. The DEFAULT
   * layer contains splitters. The PALETTE layer contains all sliding tool windows.
   */
  private final MyLayeredPane myLayeredPane;
  /*
   * Splitters.
   */
  private final ThreeComponentsSplitter myVerticalSplitter;
  private final ThreeComponentsSplitter myHorizontalSplitter;

  /*
   * Tool stripes.
   */
  private final Stripe myLeftStripe;
  private final Stripe myRightStripe;
  private final Stripe myBottomStripe;
  private final Stripe myTopStripe;

  private final List<Stripe> myStripes = new ArrayList<>();

  private final ToolWindowManagerImpl myManager;

  private boolean myStripesOverlayed;
  private boolean myWidescreen;
  private boolean myLeftHorizontalSplit;
  private boolean myRightHorizontalSplit;

  ToolWindowsPane(@NotNull JFrame frame, @NotNull ToolWindowManagerImpl manager, @NotNull Disposable parentDisposable) {
    myManager = manager;

    setOpaque(false);
    myFrame = frame;

    // splitters
    myVerticalSplitter = new ThreeComponentsSplitter(true, parentDisposable);
    RegistryValue registryValue = Registry.get("ide.mainSplitter.min.size");
    registryValue.addListener(new RegistryValueListener.Adapter() {
      @Override
      public void afterValueChanged(@NotNull RegistryValue value) {
        updateInnerMinSize(value);
      }
    }, parentDisposable);
    myVerticalSplitter.setDividerWidth(0);
    myVerticalSplitter.setDividerMouseZoneSize(Registry.intValue("ide.splitter.mouseZone"));
    myVerticalSplitter.setBackground(Color.gray);
    myHorizontalSplitter = new ThreeComponentsSplitter(false, parentDisposable);
    myHorizontalSplitter.setDividerWidth(0);
    myHorizontalSplitter.setDividerMouseZoneSize(Registry.intValue("ide.splitter.mouseZone"));
    myHorizontalSplitter.setBackground(Color.gray);
    updateInnerMinSize(registryValue);
    myWidescreen = UISettings.getInstance().getWideScreenSupport();
    myLeftHorizontalSplit = UISettings.getInstance().getLeftHorizontalSplit();
    myRightHorizontalSplit = UISettings.getInstance().getRightHorizontalSplit();
    if (myWidescreen) {
      myHorizontalSplitter.setInnerComponent(myVerticalSplitter);
    }
    else {
      myVerticalSplitter.setInnerComponent(myHorizontalSplitter);
    }

    // tool stripes
    myTopStripe = new Stripe(SwingConstants.TOP, manager);
    myStripes.add(myTopStripe);
    myLeftStripe = new Stripe(SwingConstants.LEFT, manager);
    myStripes.add(myLeftStripe);
    myBottomStripe = new Stripe(SwingConstants.BOTTOM, manager);
    myStripes.add(myBottomStripe);
    myRightStripe = new Stripe(SwingConstants.RIGHT, manager);
    myStripes.add(myRightStripe);

    updateToolStripesVisibility();

    // layered pane
    myLayeredPane = new MyLayeredPane(myWidescreen ? myHorizontalSplitter : myVerticalSplitter);

    // compose layout
    add(myTopStripe, JLayeredPane.POPUP_LAYER);
    add(myLeftStripe, JLayeredPane.POPUP_LAYER);
    add(myBottomStripe, JLayeredPane.POPUP_LAYER);
    add(myRightStripe, JLayeredPane.POPUP_LAYER);
    add(myLayeredPane, JLayeredPane.DEFAULT_LAYER);

    setFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
  }

  private void updateInnerMinSize(@NotNull RegistryValue value) {
    int minSize = Math.max(0, Math.min(100, value.asInteger()));
    myVerticalSplitter.setMinSize(JBUIScale.scale(minSize));
    myHorizontalSplitter.setMinSize(JBUIScale.scale(minSize));
  }

  @Override
  public void doLayout() {
    Dimension size = getSize();
    if (!myTopStripe.isVisible()) {
      myTopStripe.setBounds(0, 0, 0, 0);
      myBottomStripe.setBounds(0, 0, 0, 0);
      myLeftStripe.setBounds(0, 0, 0, 0);
      myRightStripe.setBounds(0, 0, 0, 0);
      myLayeredPane.setBounds(0, 0, getWidth(), getHeight());
    }
    else {
      Dimension topSize = myTopStripe.getPreferredSize();
      Dimension bottomSize = myBottomStripe.getPreferredSize();
      Dimension leftSize = myLeftStripe.getPreferredSize();
      Dimension rightSize = myRightStripe.getPreferredSize();

      myTopStripe.setBounds(0, 0, size.width, topSize.height);
      myLeftStripe.setBounds(0, topSize.height, leftSize.width, size.height - topSize.height - bottomSize.height);
      myRightStripe
        .setBounds(size.width - rightSize.width, topSize.height, rightSize.width, size.height - topSize.height - bottomSize.height);
      myBottomStripe.setBounds(0, size.height - bottomSize.height, size.width, bottomSize.height);

      if (UISettings.getInstance().getHideToolStripes() || UISettings.getInstance().getPresentationMode()) {
        myLayeredPane.setBounds(0, 0, size.width, size.height);
      }
      else {
        myLayeredPane.setBounds(leftSize.width, topSize.height, size.width - leftSize.width - rightSize.width,
                                size.height - topSize.height - bottomSize.height);
      }
    }
  }

  /**
   * Invoked when enclosed frame is being shown.
   */
  @Override
  public final void addNotify() {
    super.addNotify();
  }

  public Project getProject() {
    return myManager.getProject();
  }

  @Override
  public final void uiSettingsChanged(final UISettings uiSettings) {
    updateToolStripesVisibility();
    updateLayout();
  }

  /**
   * Creates command which adds button into the specified tool stripe.
   * Command uses copy of passed {@code info} object.
   *
   * @param button         button which should be added.
   * @param info           window info for the corresponded tool window.
   * @param comparator     which is used to sort buttons within the stripe.
   * @param finishCallBack invoked when the command is completed.
   */
  @NotNull
  final FinalizableCommand createAddButtonCmd(final StripeButton button,
                                              @NotNull WindowInfoImpl info,
                                              @NotNull Comparator<? super StripeButton> comparator,
                                              @NotNull Runnable finishCallBack) {
    final WindowInfoImpl copiedInfo = info.copy();
    myIdToButton.put(copiedInfo.getId(), button);
    return new AddToolStripeButtonCmd(button, copiedInfo, comparator, finishCallBack);
  }

  /**
   * Creates command which shows tool window with specified set of parameters.
   * Command uses cloned copy of passed {@code info} object.
   *
   * @param dirtyMode if {@code true} then JRootPane will not be validated and repainted after adding
   *                  the decorator. Moreover in this (dirty) mode animation doesn't work.
   */
  @NotNull
  final FinalizableCommand createAddDecoratorCmd(@NotNull InternalDecorator decorator,
                                                 @NotNull WindowInfoImpl info,
                                                 final boolean dirtyMode,
                                                 @NotNull Runnable finishCallBack) {
    WindowInfoImpl copiedInfo = info.copy();
    myDecoratorToInfo.put(decorator, copiedInfo);

    if (info.isDocked()) {
      WindowInfoImpl sideInfo = getDockedInfoAt(info.getAnchor(), !info.isSplit());
      return sideInfo == null
             ? new AddDockedComponentCmd(decorator, info, dirtyMode, finishCallBack)
             : new AddAndSplitDockedComponentCmd(decorator, info, dirtyMode, finishCallBack);
    }
    else if (info.isSliding()) {
      return new AddSlidingComponentCmd(decorator, info, dirtyMode, finishCallBack);
    }
    else {
      throw new IllegalArgumentException("Unknown window type: " + info.getType());
    }
  }

  /**
   * Creates command which removes tool button from tool stripe.
   *
   * @param id {@code ID} of the button to be removed.
   */
  @Nullable
  final FinalizableCommand createRemoveButtonCmd(@NotNull WindowInfoImpl info, @NotNull String id, @NotNull Runnable finishCallBack) {
    StripeButton button = myIdToButton.remove(id);
    if (button == null) {
      return null;
    }

    return new RemoveToolStripeButtonCmd(button, info, finishCallBack);
  }

  /**
   * Creates command which hides tool window with specified set of parameters.
   *
   * @param dirtyMode if {@code true} then JRootPane will not be validated and repainted after removing
   *                  the decorator. Moreover in this (dirty) mode animation doesn't work.
   */
  @NotNull
  final FinalizableCommand createRemoveDecoratorCmd(@NotNull String id, boolean dirtyMode, @NotNull Runnable finishCallBack) {
    Component decorator = myManager.getInternalDecorator(id);
    WindowInfoImpl info = getDecoratorInfoById(id);
    LOG.assertTrue(info != null, "WindowInfo not found: id = " + id);

    myDecoratorToInfo.remove(decorator);

    if (info.isDocked()) {
      WindowInfoImpl sideInfo = getDockedInfoAt(info.getAnchor(), !info.isSplit());
      return sideInfo == null
             ? new RemoveDockedComponentCmd(info, dirtyMode, finishCallBack)
             : new RemoveSplitAndDockedComponentCmd(info, dirtyMode, finishCallBack);
    }
    else if (info.isSliding()) {
      return new RemoveSlidingComponentCmd(decorator, info, dirtyMode, finishCallBack);
    }
    else {
      throw new IllegalArgumentException("Unknown window type");
    }
  }

  @NotNull
  final FinalizableCommand createTransferFocusCmd(@NotNull BooleanSupplier shouldSkip, @NotNull Runnable finishCallBack) {
    return new TransferFocusCmd(shouldSkip, finishCallBack);
  }

  /**
   * Creates command which sets specified document component.
   *
   * @param component component to be set.
   */
  @NotNull
  final FinalizableCommand createSetEditorComponentCmd(@Nullable JComponent component, @NotNull Runnable finishCallBack) {
    return new SetEditorComponentCmd(component, finishCallBack);
  }

  @NotNull
  final FinalizableCommand createUpdateButtonPositionCmd(@NotNull String id, @NotNull Runnable finishCallback) {
    return new UpdateButtonPositionCmd(id, finishCallback);
  }

  @NotNull
  public final JComponent getMyLayeredPane() {
    return myLayeredPane;
  }

  /**
   * @param id {@code ID} of decorator.
   * @return {@code WindowInfo} associated with specified window decorator.
   */
  private WindowInfoImpl getDecoratorInfoById(@NotNull String id) {
    return myDecoratorToInfo.get(myManager.getInternalDecorator(id));
  }

  /**
   * Sets (docks) specified component to the specified anchor.
   */
  private void setComponent(final JComponent component, @NotNull ToolWindowAnchor anchor, final float weight) {
    if (ToolWindowAnchor.TOP == anchor) {
      myVerticalSplitter.setFirstComponent(component);
      myVerticalSplitter.setFirstSize((int)(myLayeredPane.getHeight() * weight));
    }
    else if (ToolWindowAnchor.LEFT == anchor) {
      myHorizontalSplitter.setFirstComponent(component);
      myHorizontalSplitter.setFirstSize((int)(myLayeredPane.getWidth() * weight));
    }
    else if (ToolWindowAnchor.BOTTOM == anchor) {
      myVerticalSplitter.setLastComponent(component);
      myVerticalSplitter.setLastSize((int)(myLayeredPane.getHeight() * weight));
    }
    else if (ToolWindowAnchor.RIGHT == anchor) {
      myHorizontalSplitter.setLastComponent(component);
      myHorizontalSplitter.setLastSize((int)(myLayeredPane.getWidth() * weight));
    }
    else {
      LOG.error("unknown anchor: " + anchor);
    }
  }

  private JComponent getComponentAt(@NotNull ToolWindowAnchor anchor) {
    if (ToolWindowAnchor.TOP == anchor) {
      return myVerticalSplitter.getFirstComponent();
    }
    else if (ToolWindowAnchor.LEFT == anchor) {
      return myHorizontalSplitter.getFirstComponent();
    }
    else if (ToolWindowAnchor.BOTTOM == anchor) {
      return myVerticalSplitter.getLastComponent();
    }
    else if (ToolWindowAnchor.RIGHT == anchor) {
      return myHorizontalSplitter.getLastComponent();
    }
    else {
      LOG.error("unknown anchor: " + anchor);
      return null;
    }
  }

  private float getPreferredSplitProportion(@NotNull String id, float defaultValue) {
    float f = myIdToSplitProportion.get(id);
    return f == 0 ? defaultValue : f;
  }

  private WindowInfoImpl getDockedInfoAt(@NotNull ToolWindowAnchor anchor, boolean side) {
    for (WindowInfoImpl info : myDecoratorToInfo.values()) {
      if (info.isVisible() && info.isDocked() && info.getAnchor() == anchor && side == info.isSplit()) {
        return info;
      }
    }

    return null;
  }

  private void setDocumentComponent(@Nullable JComponent component) {
    (myWidescreen ? myVerticalSplitter : myHorizontalSplitter).setInnerComponent(component);
  }

  private void updateToolStripesVisibility() {
    boolean oldVisible = myLeftStripe.isVisible();

    final boolean showButtons = !UISettings.getInstance().getHideToolStripes() && !UISettings.getInstance().getPresentationMode();
    boolean visible = showButtons || myStripesOverlayed;
    myLeftStripe.setVisible(visible);
    myRightStripe.setVisible(visible);
    myTopStripe.setVisible(visible);
    myBottomStripe.setVisible(visible);

    boolean overlayed = !showButtons && myStripesOverlayed;

    myLeftStripe.setOverlayed(overlayed);
    myRightStripe.setOverlayed(overlayed);
    myTopStripe.setOverlayed(overlayed);
    myBottomStripe.setOverlayed(overlayed);

    if (oldVisible != visible) {
      revalidate();
      repaint();
    }
  }

  public int getBottomHeight() {
    return myBottomStripe.isVisible() ? myBottomStripe.getHeight() : 0;
  }

  public boolean isBottomSideToolWindowsVisible() {
    return getComponentAt(ToolWindowAnchor.BOTTOM) != null;
  }

  @Nullable
  Stripe getStripeFor(@NotNull String id) {
    ToolWindow window = myManager.getToolWindow(id);
    if (window == null) {
      return null;
    }

    ToolWindowAnchor anchor = Objects.requireNonNull(myManager.getToolWindow(id)).getAnchor();
    if (ToolWindowAnchor.TOP == anchor) {
      return myTopStripe;
    }
    if (ToolWindowAnchor.BOTTOM == anchor) {
      return myBottomStripe;
    }
    if (ToolWindowAnchor.LEFT == anchor) {
      return myLeftStripe;
    }
    if (ToolWindowAnchor.RIGHT == anchor) {
      return myRightStripe;
    }

    throw new IllegalArgumentException("Anchor=" + anchor);
  }

  @Nullable
  Stripe getStripeFor(@NotNull Rectangle screenRec, @NotNull Stripe preferred) {
    if (preferred.containsScreen(screenRec)) {
      return myStripes.get(myStripes.indexOf(preferred));
    }

    for (Stripe each : myStripes) {
      if (each.containsScreen(screenRec)) {
        return myStripes.get(myStripes.indexOf(each));
      }
    }

    return null;
  }

  void startDrag() {
    for (Stripe each : myStripes) {
      each.startDrag();
    }
  }

  void stopDrag() {
    for (Stripe each : myStripes) {
      each.stopDrag();
    }
  }

  void stretchWidth(@NotNull ToolWindow wnd, int value) {
    stretch(wnd, value);
  }

  void stretchHeight(@NotNull ToolWindow wnd, int value) {
    stretch(wnd, value);
  }

  private void stretch(@NotNull ToolWindow wnd, int value) {
    Pair<Resizer, Component> pair = findResizerAndComponent(wnd);
    if (pair == null) return;

    boolean vertical = wnd.getAnchor() == ToolWindowAnchor.TOP || wnd.getAnchor() == ToolWindowAnchor.BOTTOM;
    int actualSize = (vertical ? pair.second.getHeight() : pair.second.getWidth()) + value;
    boolean first = wnd.getAnchor() == ToolWindowAnchor.LEFT  || wnd.getAnchor() == ToolWindowAnchor.TOP;
    int maxValue = vertical ? myVerticalSplitter.getMaxSize(first) : myHorizontalSplitter.getMaxSize(first);
    int minValue = vertical ? myVerticalSplitter.getMinSize(first) : myHorizontalSplitter.getMinSize(first);

    pair.first.setSize(Math.max(minValue, Math.min(maxValue, actualSize)));
  }

  @Nullable
  private Pair<Resizer, Component> findResizerAndComponent(@NotNull ToolWindow wnd) {
    if (!wnd.isVisible()) return null;

    Resizer resizer = null;
    Component cmp = null;

    if (wnd.getType() == ToolWindowType.DOCKED) {
      cmp = getComponentAt(wnd.getAnchor());

      if (cmp != null) {
        if (wnd.getAnchor().isHorizontal()) {
          resizer = myVerticalSplitter.getFirstComponent() == cmp
                    ? new Resizer.Splitter.FirstComponent(myVerticalSplitter)
                    : new Resizer.Splitter.LastComponent(myVerticalSplitter);
        }
        else {
          resizer = myHorizontalSplitter.getFirstComponent() == cmp
                    ? new Resizer.Splitter.FirstComponent(myHorizontalSplitter)
                    : new Resizer.Splitter.LastComponent(myHorizontalSplitter);
        }
      }
    }
    else if (wnd.getType() == ToolWindowType.SLIDING) {
      cmp = wnd.getComponent();
      while (cmp != null) {
        if (cmp.getParent() == myLayeredPane) break;
        cmp = cmp.getParent();
      }

      if (cmp != null) {
        if (wnd.getAnchor() == ToolWindowAnchor.TOP) {
          resizer = new Resizer.LayeredPane.Top(cmp);
        }
        else if (wnd.getAnchor() == ToolWindowAnchor.BOTTOM) {
          resizer = new Resizer.LayeredPane.Bottom(cmp);
        }
        else if (wnd.getAnchor() == ToolWindowAnchor.LEFT) {
          resizer = new Resizer.LayeredPane.Left(cmp);
        }
        else if (wnd.getAnchor() == ToolWindowAnchor.RIGHT) {
          resizer = new Resizer.LayeredPane.Right(cmp);
        }
      }
    }

    return resizer != null ? Pair.create(resizer, cmp) : null;
  }

  private void updateLayout() {
    UISettings uiSettings = UISettings.getInstance();
    if (myWidescreen != uiSettings.getWideScreenSupport()) {
      JComponent documentComponent = (myWidescreen ? myVerticalSplitter : myHorizontalSplitter).getInnerComponent();
      myWidescreen = uiSettings.getWideScreenSupport();
      if (myWidescreen) {
        myVerticalSplitter.setInnerComponent(null);
        myHorizontalSplitter.setInnerComponent(myVerticalSplitter);
      }
      else {
        myHorizontalSplitter.setInnerComponent(null);
        myVerticalSplitter.setInnerComponent(myHorizontalSplitter);
      }
      myLayeredPane.remove(myWidescreen ? myVerticalSplitter : myHorizontalSplitter);
      myLayeredPane.add(myWidescreen ? myHorizontalSplitter : myVerticalSplitter, DEFAULT_LAYER);
      setDocumentComponent(documentComponent);
    }
    if (myLeftHorizontalSplit != uiSettings.getLeftHorizontalSplit()) {
      JComponent component = getComponentAt(ToolWindowAnchor.LEFT);
      if (component instanceof Splitter) {
        Splitter splitter = (Splitter)component;
        InternalDecorator first = (InternalDecorator)splitter.getFirstComponent();
        InternalDecorator second = (InternalDecorator)splitter.getSecondComponent();
        setComponent(splitter, ToolWindowAnchor.LEFT, ToolWindowAnchor.LEFT.isSplitVertically()
                                                      ? first.getWindowInfo().getWeight()
                                                      : first.getWindowInfo().getWeight() + second.getWindowInfo().getWeight());
      }
      myLeftHorizontalSplit = uiSettings.getLeftHorizontalSplit();
    }
    if (myRightHorizontalSplit != uiSettings.getRightHorizontalSplit()) {
      JComponent component = getComponentAt(ToolWindowAnchor.RIGHT);
      if (component instanceof Splitter) {
        Splitter splitter = (Splitter)component;
        InternalDecorator first = (InternalDecorator)splitter.getFirstComponent();
        InternalDecorator second = (InternalDecorator)splitter.getSecondComponent();
        setComponent(splitter, ToolWindowAnchor.RIGHT, ToolWindowAnchor.RIGHT.isSplitVertically()
                                                       ? first.getWindowInfo().getWeight()
                                                       : first.getWindowInfo().getWeight() + second.getWindowInfo().getWeight());
      }
      myRightHorizontalSplit = uiSettings.getRightHorizontalSplit();
    }
  }

  public boolean isMaximized(@NotNull ToolWindow wnd) {
      return myMaximizedProportion != null && myMaximizedProportion.first == wnd;
  }

  void setMaximized(@NotNull ToolWindow wnd, boolean maximized) {
    Pair<Resizer, Component> resizerAndComponent = findResizerAndComponent(wnd);
    if (resizerAndComponent == null) return;

    if (!maximized) {
      ToolWindow maximizedWindow = myMaximizedProportion.first;
      assert maximizedWindow == wnd;
      resizerAndComponent.first.setSize(myMaximizedProportion.second);
      myMaximizedProportion = null;
    } else {
      int size = wnd.getAnchor().isHorizontal() ? resizerAndComponent.second.getHeight() : resizerAndComponent.second.getWidth();
      stretch(wnd, Short.MAX_VALUE);
      myMaximizedProportion = Pair.create(wnd, size);
    }
    doLayout();
  }


  @FunctionalInterface
  interface Resizer {
    void setSize(int size);


    abstract class Splitter implements Resizer {
      ThreeComponentsSplitter mySplitter;

      Splitter(@NotNull ThreeComponentsSplitter splitter) {
        mySplitter = splitter;
      }

      static class FirstComponent extends Splitter {
        FirstComponent(@NotNull ThreeComponentsSplitter splitter) {
          super(splitter);
        }

        @Override
        public void setSize(int size) {
          mySplitter.setFirstSize(size);
        }
      }

      static class LastComponent extends Splitter {
        LastComponent(@NotNull ThreeComponentsSplitter splitter) {
          super(splitter);
        }

        @Override
        public void setSize(int size) {
          mySplitter.setLastSize(size);
        }
      }
    }

    abstract class LayeredPane implements Resizer {
      Component myComponent;

      LayeredPane(@NotNull Component component) {
        myComponent = component;
      }

      @Override
      public final void setSize(int size) {
        _setSize(size);
        if (myComponent.getParent() instanceof JComponent) {
          JComponent parent = (JComponent)myComponent;
          parent.revalidate();
          parent.repaint();
        }
      }

      abstract void _setSize(int size);

      static class Left extends LayeredPane {

        Left(@NotNull Component component) {
          super(component);
        }

        @Override
        public void _setSize(int size) {
          myComponent.setSize(size, myComponent.getHeight());
        }
      }

      static class Right extends LayeredPane {
        Right(@NotNull Component component) {
          super(component);
        }

        @Override
        public void _setSize(int size) {
          Rectangle bounds = myComponent.getBounds();
          int delta = size - bounds.width;
          bounds.x -= delta;
          bounds.width += delta;
          myComponent.setBounds(bounds);
        }
      }

      static class Top extends LayeredPane {
        Top(@NotNull Component component) {
          super(component);
        }

        @Override
        public void _setSize(int size) {
          myComponent.setSize(myComponent.getWidth(), size);
        }
      }

      static class Bottom extends LayeredPane {
        Bottom(@NotNull Component component) {
          super(component);
        }

        @Override
        public void _setSize(int size) {
          Rectangle bounds = myComponent.getBounds();
          int delta = size - bounds.height;
          bounds.y -= delta;
          bounds.height += delta;
          myComponent.setBounds(bounds);
        }
      }
    }
  }

  private final class AddDockedComponentCmd extends FinalizableCommand {
    private final JComponent myComponent;
    private final WindowInfoImpl myInfo;
    private final boolean myDirtyMode;

    AddDockedComponentCmd(@NotNull JComponent component,
                          @NotNull WindowInfoImpl info,
                          final boolean dirtyMode,
                          @NotNull Runnable finishCallBack) {
      super(finishCallBack);
      myComponent = component;
      myInfo = info;
      myDirtyMode = dirtyMode;
    }

    @Override
    public final void run() {
      try {
        final ToolWindowAnchor anchor = myInfo.getAnchor();
        setComponent(myComponent, anchor, normalizeWeigh(myInfo.getWeight()));
        if (!myDirtyMode) {
          myLayeredPane.validate();
          myLayeredPane.repaint();
        }
      }
      finally {
        finish();
      }
    }
  }

  private final class AddAndSplitDockedComponentCmd extends FinalizableCommand {
    private final JComponent myNewComponent;
    private final WindowInfoImpl myInfo;
    private final boolean myDirtyMode;

    private AddAndSplitDockedComponentCmd(@NotNull JComponent newComponent,
                                          @NotNull WindowInfoImpl info,
                                          final boolean dirtyMode,
                                          @NotNull Runnable finishCallBack) {
      super(finishCallBack);
      myNewComponent = newComponent;
      myInfo = info;
      myDirtyMode = dirtyMode;
    }

    @Override
    public void run() {
      try {
        final ToolWindowAnchor anchor = myInfo.getAnchor();
        class MySplitter extends OnePixelSplitter implements UISettingsListener {
          @Override
          public void uiSettingsChanged(UISettings uiSettings) {
            if (anchor == ToolWindowAnchor.LEFT) {
              setOrientation(!uiSettings.getLeftHorizontalSplit());
            }
            else if (anchor == ToolWindowAnchor.RIGHT) {
              setOrientation(!uiSettings.getRightHorizontalSplit());
            }
          }

          @Override
          public String toString() {
            return "[" + getFirstComponent() + "|" + getSecondComponent() + "]";
          }
        }
        Splitter splitter = new MySplitter();
        splitter.setOrientation(anchor.isSplitVertically());
        if (!anchor.isHorizontal()) {
          splitter.setAllowSwitchOrientationByMouseClick(true);
          splitter.addPropertyChangeListener(evt -> {
            if (!Splitter.PROP_ORIENTATION.equals(evt.getPropertyName())) return;
            boolean isSplitterHorizontalNow = !splitter.isVertical();
            UISettings settings = UISettings.getInstance();
            if (anchor == ToolWindowAnchor.LEFT) {
              if (settings.getLeftHorizontalSplit() != isSplitterHorizontalNow) {
                settings.setLeftHorizontalSplit(isSplitterHorizontalNow);
                settings.fireUISettingsChanged();
              }
            }
            if (anchor == ToolWindowAnchor.RIGHT) {
              if (settings.getRightHorizontalSplit() != isSplitterHorizontalNow) {
                settings.setRightHorizontalSplit(isSplitterHorizontalNow);
                settings.fireUISettingsChanged();
              }
            }
          });
        }
        JComponent c = getComponentAt(anchor);
        //If all components are hidden for anchor we should find the second component to put in a splitter
        //Otherwise we add empty splitter
        if (c == null) {
          List<String> ids = ToolWindowsPane.this.myManager.getIdsOn(anchor);
          ids.remove(myInfo.getId());
          for (Iterator<String> iterator = ids.iterator(); iterator.hasNext(); ) {
            String id = iterator.next();
            ToolWindow window = myManager.getToolWindow(id);
            if (window == null || window.isSplitMode() == myInfo.isSplit() || !window.isVisible()) iterator.remove();
          }
          if (!ids.isEmpty()) {
            c = myManager.getInternalDecorator(ids.get(0));
          }
          if (c == null) {
            LOG.error("Empty splitter @ " + anchor + " during AddAndSplitDockedComponentCmd for " + myInfo.getId());
          }
        }
        float newWeight;
        if (c instanceof InternalDecorator) {
          InternalDecorator oldComponent = (InternalDecorator)c;
          WindowInfoImpl oldInfo = oldComponent.getWindowInfo();

          IJSwingUtilities.updateComponentTreeUI(oldComponent);
          IJSwingUtilities.updateComponentTreeUI(myNewComponent);

          if (myInfo.isSplit()) {
            splitter.setFirstComponent(oldComponent);
            splitter.setSecondComponent(myNewComponent);
            float proportion = getPreferredSplitProportion(oldInfo.getId(),
                                                           normalizeWeigh(oldInfo.getSideWeight() /
                                                                          (oldInfo.getSideWeight() +
                                                                           myInfo.getSideWeight())));
            splitter.setProportion(proportion);
            if (!anchor.isHorizontal() && !anchor.isSplitVertically()) {
              newWeight = normalizeWeigh(oldInfo.getWeight() + myInfo.getWeight());
            }
            else {
              newWeight = normalizeWeigh(oldInfo.getWeight());
            }
          }
          else {
            splitter.setFirstComponent(myNewComponent);
            splitter.setSecondComponent(oldComponent);
            splitter.setProportion(normalizeWeigh(myInfo.getSideWeight()));
            if (!anchor.isHorizontal() && !anchor.isSplitVertically()) {
              newWeight = normalizeWeigh(oldInfo.getWeight() + myInfo.getWeight());
            }
            else {
              newWeight = normalizeWeigh(myInfo.getWeight());
            }
          }
        } else {
          newWeight = normalizeWeigh(myInfo.getWeight());
        }
        setComponent(splitter, anchor, newWeight);

        if (!myDirtyMode) {
          myLayeredPane.validate();
          myLayeredPane.repaint();
        }
      }
      finally {
        finish();
      }
    }
  }

  private final class AddSlidingComponentCmd extends FinalizableCommand {
    private final JComponent myComponent;
    private final WindowInfoImpl myInfo;
    private final boolean myDirtyMode;

    AddSlidingComponentCmd(@NotNull JComponent component,
                           @NotNull WindowInfoImpl info,
                           final boolean dirtyMode,
                           @NotNull Runnable finishCallBack) {
      super(finishCallBack);
      myComponent = component;
      myInfo = info;
      myDirtyMode = dirtyMode;
    }
    @Override
    public final void run() {
      try {
        // Show component.
        if (!myDirtyMode && UISettings.getInstance().getAnimateWindows() && !RemoteDesktopService.isRemoteSession()) {
          // Prepare top image. This image is scrolling over bottom image.
          final Image topImage = myLayeredPane.getTopImage();

          Rectangle bounds =  myComponent.getBounds();

          useSafely(topImage.getGraphics(), topGraphics -> {
            myComponent.putClientProperty(TEMPORARY_ADDED, Boolean.TRUE);
            try {
              myLayeredPane.add(myComponent, JLayeredPane.PALETTE_LAYER);
              myLayeredPane.moveToFront(myComponent);
              myLayeredPane.setBoundsInPaletteLayer(myComponent, myInfo.getAnchor(), myInfo.getWeight());
              myComponent.paint(topGraphics);
              myLayeredPane.remove(myComponent);
            } finally {
              myComponent.putClientProperty(TEMPORARY_ADDED, null);
            }
          });

          // Prepare bottom image.
          final Image bottomImage = myLayeredPane.getBottomImage();

          Point2D bottomImageOffset = PaintUtil.getFractOffsetInRootPane(myLayeredPane);
          useSafely(bottomImage.getGraphics(), bottomGraphics -> {
            bottomGraphics.setClip(0, 0, bounds.width, bounds.height);
            bottomGraphics.translate(bottomImageOffset.getX() - bounds.x, bottomImageOffset.getY() - bounds.y);
            myLayeredPane.paint(bottomGraphics);
          });

          // Start animation.
          final Surface surface = new Surface(topImage, bottomImage, PaintUtil.negate(bottomImageOffset), 1, myInfo.getAnchor(), UISettings.ANIMATION_DURATION);
          myLayeredPane.add(surface, JLayeredPane.PALETTE_LAYER);
          surface.setBounds(bounds);
          myLayeredPane.validate();
          myLayeredPane.repaint();

          surface.runMovement();
          myLayeredPane.remove(surface);
          myLayeredPane.add(myComponent, JLayeredPane.PALETTE_LAYER);
        }
        else { // not animated
          myLayeredPane.add(myComponent, JLayeredPane.PALETTE_LAYER);
          myLayeredPane.setBoundsInPaletteLayer(myComponent, myInfo.getAnchor(), myInfo.getWeight());
        }
        if (!myDirtyMode) {
          myLayeredPane.validate();
          myLayeredPane.repaint();
        }
      }
      finally {
        finish();
      }
    }
  }

  private final class AddToolStripeButtonCmd extends FinalizableCommand {
    private final StripeButton myButton;
    private final WindowInfoImpl myInfo;
    private final Comparator<? super StripeButton> myComparator;

    AddToolStripeButtonCmd(final StripeButton button,
                           @NotNull WindowInfoImpl info,
                           @NotNull Comparator<? super StripeButton> comparator,
                           @NotNull Runnable finishCallBack) {
      super(finishCallBack);
      myButton = button;
      myInfo = info;
      myComparator = comparator;
    }

    @Override
    public final void run() {
      try {
        final ToolWindowAnchor anchor = myInfo.getAnchor();
        if (ToolWindowAnchor.TOP == anchor) {
          myTopStripe.addButton(myButton, myComparator);
        }
        else if (ToolWindowAnchor.LEFT == anchor) {
          myLeftStripe.addButton(myButton, myComparator);
        }
        else if (ToolWindowAnchor.BOTTOM == anchor) {
          myBottomStripe.addButton(myButton, myComparator);
        }
        else if (ToolWindowAnchor.RIGHT == anchor) {
          myRightStripe.addButton(myButton, myComparator);
        }
        else {
          LOG.error("unknown anchor: " + anchor);
        }
        validate();
        repaint();
      }
      finally {
        finish();
      }
    }
  }

  private final class RemoveToolStripeButtonCmd extends FinalizableCommand {
    private final StripeButton myButton;
    private final ToolWindowAnchor myAnchor;

    RemoveToolStripeButtonCmd(@NotNull StripeButton button, @NotNull WindowInfoImpl info, @NotNull Runnable finishCallBack) {
      super(finishCallBack);
      myButton = button;
      myAnchor = info.getAnchor();
    }

    @Override
    public final void run() {
      try {
        if (ToolWindowAnchor.TOP == myAnchor) {
          myTopStripe.removeButton(myButton);
        }
        else if (ToolWindowAnchor.LEFT == myAnchor) {
          myLeftStripe.removeButton(myButton);
        }
        else if (ToolWindowAnchor.BOTTOM == myAnchor) {
          myBottomStripe.removeButton(myButton);
        }
        else if (ToolWindowAnchor.RIGHT == myAnchor) {
          myRightStripe.removeButton(myButton);
        }
        else {
          LOG.error("unknown anchor: " + myAnchor);
        }
        validate();
        repaint();
      }
      finally {
        finish();
      }
    }
  }

  private final class TransferFocusCmd extends FinalizableCommand {
    private final BooleanSupplier shouldSkip;

    TransferFocusCmd(BooleanSupplier shouldSkip, Runnable finishCallback) {
      super(finishCallback);
      this.shouldSkip = shouldSkip;
    }

    @Override
    public void run() {
      if (shouldSkip.getAsBoolean()) return;
      transferFocus();
    }
  }

  private final class RemoveDockedComponentCmd extends FinalizableCommand {
    private final WindowInfoImpl myInfo;
    private final boolean myDirtyMode;

    RemoveDockedComponentCmd(@NotNull WindowInfoImpl info, final boolean dirtyMode, @NotNull Runnable finishCallBack) {
      super(finishCallBack);
      myInfo = info;
      myDirtyMode = dirtyMode;
    }

    @Override
    public final void run() {
      try {
        setComponent(null, myInfo.getAnchor(), 0);
        if (!myDirtyMode) {
          myLayeredPane.validate();
          myLayeredPane.repaint();
        }
      }
      finally {
        finish();
      }
    }
  }

  private final class RemoveSplitAndDockedComponentCmd extends FinalizableCommand {
    private final WindowInfoImpl myInfo;
    private final boolean myDirtyMode;

    private RemoveSplitAndDockedComponentCmd(@NotNull WindowInfoImpl info,
                                             boolean dirtyMode,
                                             @NotNull Runnable finishCallBack) {
      super(finishCallBack);

      myInfo = info;
      myDirtyMode = dirtyMode;
    }

    @Override
    public void run() {
      try {
        ToolWindowAnchor anchor = myInfo.getAnchor();
        JComponent c = getComponentAt(anchor);
        if (c instanceof Splitter) {
          Splitter splitter = (Splitter)c;
          InternalDecorator component =
            myInfo.isSplit() ? (InternalDecorator)splitter.getFirstComponent() : (InternalDecorator)splitter.getSecondComponent();
          if (myInfo.isSplit() && component != null) {
            myIdToSplitProportion.put(component.getWindowInfo().getId(), splitter.getProportion());
          }
          setComponent(component, anchor, component != null ? component.getWindowInfo().getWeight() : 0);
        }
        else {
          setComponent(null, anchor, 0);
        }
        if (!myDirtyMode) {
          myLayeredPane.validate();
          myLayeredPane.repaint();
        }
        transferFocus();
      }
      finally {
        finish();
      }
    }
  }

  private final class RemoveSlidingComponentCmd extends FinalizableCommand {
    private final Component myComponent;
    private final WindowInfoImpl myInfo;
    private final boolean myDirtyMode;

    RemoveSlidingComponentCmd(Component component, @NotNull WindowInfoImpl info, boolean dirtyMode, @NotNull Runnable finishCallBack) {
      super(finishCallBack);
      myComponent = component;
      myInfo = info;
      myDirtyMode = dirtyMode;
    }
    @Override
    public final void run() {
      try {
        final UISettings uiSettings = UISettings.getInstance();
        if (!myDirtyMode && uiSettings.getAnimateWindows() && !RemoteDesktopService.isRemoteSession()) {
          final Rectangle bounds = myComponent.getBounds();
          // Prepare top image. This image is scrolling over bottom image. It contains
          // picture of component is being removed.
          final Image topImage = myLayeredPane.getTopImage();
          useSafely(topImage.getGraphics(), topGraphics -> myComponent.paint(topGraphics));

          // Prepare bottom image. This image contains picture of component that is located
          // under the component to is being removed.
          final Image bottomImage = myLayeredPane.getBottomImage();

          Point2D bottomImageOffset = PaintUtil.getFractOffsetInRootPane(myLayeredPane);
          useSafely(bottomImage.getGraphics(), bottomGraphics -> {
            myLayeredPane.remove(myComponent);
            bottomGraphics.clipRect(0, 0, bounds.width, bounds.height);
            bottomGraphics.translate(bottomImageOffset.getX() - bounds.x, bottomImageOffset.getY() - bounds.y);
            myLayeredPane.paint(bottomGraphics);
          });

          // Remove component from the layered pane and start animation.
          final Surface surface = new Surface(topImage, bottomImage, PaintUtil.negate(bottomImageOffset), -1, myInfo.getAnchor(), UISettings.ANIMATION_DURATION);
          myLayeredPane.add(surface, JLayeredPane.PALETTE_LAYER);
          surface.setBounds(bounds);
          myLayeredPane.validate();
          myLayeredPane.repaint();

          surface.runMovement();
          myLayeredPane.remove(surface);
        }
        else { // not animated
          myLayeredPane.remove(myComponent);
        }
        if (!myDirtyMode) {
          myLayeredPane.validate();
          myLayeredPane.repaint();
        }
        transferFocus();
      }
      finally {
        finish();
      }
    }
  }

  private final class SetEditorComponentCmd extends FinalizableCommand {
    private final JComponent myComponent;

    SetEditorComponentCmd(@Nullable JComponent component, @NotNull Runnable finishCallBack) {
      super(finishCallBack);

      myComponent = component;
    }

    @Override
    public void run() {
      try {
        setDocumentComponent(myComponent);
        myLayeredPane.validate();
        myLayeredPane.repaint();
      }
      finally {
        finish();
      }
    }
  }

  private final class UpdateButtonPositionCmd extends FinalizableCommand {
    private final String myId;

    private UpdateButtonPositionCmd(@NotNull String id, @NotNull Runnable finishCallBack) {
      super(finishCallBack);
      myId = id;
    }

    @Override
    public void run() {
      try {
        StripeButton stripeButton = myIdToButton.get(myId);
        if (stripeButton == null) {
          return;
        }

        WindowInfoImpl info = stripeButton.getWindowInfo();
        ToolWindowAnchor anchor = info.getAnchor();

        if (ToolWindowAnchor.TOP == anchor) {
          myTopStripe.revalidate();
        }
        else if (ToolWindowAnchor.LEFT == anchor) {
          myLeftStripe.revalidate();
        }
        else if (ToolWindowAnchor.BOTTOM == anchor) {
          myBottomStripe.revalidate();
        }
        else if (ToolWindowAnchor.RIGHT == anchor) {
          myRightStripe.revalidate();
        }
        else {
          LOG.error("unknown anchor: " + anchor);
        }
      }
      finally {
        finish();
      }
    }
  }

  private static final class ImageRef extends SoftReference<BufferedImage> {
    @Nullable
    private BufferedImage myStrongRef;

    ImageRef(@NotNull BufferedImage image) {
      super(image);
      myStrongRef = image;
    }

    @Override
    public BufferedImage get() {
      if (myStrongRef != null) {
        BufferedImage img = myStrongRef;
        myStrongRef = null; // drop on first request
        return img;
      }
      return super.get();
    }
  }

  private static class ImageCache extends ScaleContext.Cache<ImageRef> {
    ImageCache(@NotNull Function<? super ScaleContext, ImageRef> imageProvider) {
      super(imageProvider);
    }

    public BufferedImage get(@NotNull ScaleContext ctx) {
      ImageRef ref = getOrProvide(ctx);
      BufferedImage image = SoftReference.dereference(ref);
      if (image != null) return image;
      clear(); // clear to recalculate the image
      return get(ctx); // first recalculated image will be non-null
    }
  }

  private final class MyLayeredPane extends JBLayeredPane {
    private final Function<ScaleContext, ImageRef> myImageProvider = __ -> {
      int width = Math.max(Math.max(1, getWidth()), myFrame.getWidth());
      int height = Math.max(Math.max(1, getHeight()), myFrame.getHeight());
      return new ImageRef(ImageUtil.createImage(getGraphicsConfiguration(), width, height, BufferedImage.TYPE_INT_RGB));
    };

    /*
     * These images are used to perform animated showing and hiding of components.
     * They are the member for performance reason.
     */
    private final ImageCache myBottomImageCache = new ImageCache(myImageProvider);
    private final ImageCache myTopImageCache = new ImageCache(myImageProvider);

    MyLayeredPane(@NotNull JComponent splitter) {
      setOpaque(false);
      add(splitter, JLayeredPane.DEFAULT_LAYER);
    }

    final Image getBottomImage() {
      return myBottomImageCache.get(ScaleContext.create(this));
    }

    final Image getTopImage() {
      return myTopImageCache.get(ScaleContext.create(this));
    }

    /**
     * When component size becomes larger then bottom and top images should be enlarged.
     */
    @Override
    public void doLayout() {
      final int width = getWidth();
      final int height = getHeight();
      if (width < 0 || height < 0) {
        return;
      }
      // Resize component at the DEFAULT layer. It should be only on component in that layer
      Component[] components = getComponentsInLayer(JLayeredPane.DEFAULT_LAYER.intValue());
      LOG.assertTrue(components.length <= 1);
      for (final Component component : components) {
        component.setBounds(0, 0, getWidth(), getHeight());
      }
      // Resize components at the PALETTE layer
      components = getComponentsInLayer(JLayeredPane.PALETTE_LAYER.intValue());
      for (final Component component : components) {
        if (!(component instanceof InternalDecorator)) {
          continue;
        }
        final WindowInfoImpl info = myDecoratorToInfo.get(component);
        // In normal situation info is not null. But sometimes Swing sends resize
        // event to removed component. See SCR #19566.
        if (info == null) {
          continue;
        }

        float weight = info.getAnchor().isHorizontal()
                             ? (float)component.getHeight() / getHeight()
                             : (float)component.getWidth() / getWidth();
        setBoundsInPaletteLayer(component, info.getAnchor(), weight);
      }
    }

    final void setBoundsInPaletteLayer(@NotNull Component component, @NotNull ToolWindowAnchor anchor, float weight) {
      if (weight < .0f) {
        weight = WindowInfoImpl.DEFAULT_WEIGHT;
      }
      else if (weight > 1.0f) {
        weight = 1.0f;
      }
      if (ToolWindowAnchor.TOP == anchor) {
        component.setBounds(0, 0, getWidth(), (int)(getHeight() * weight + .5f));
      }
      else if (ToolWindowAnchor.LEFT == anchor) {
        component.setBounds(0, 0, (int)(getWidth() * weight + .5f), getHeight());
      }
      else if (ToolWindowAnchor.BOTTOM == anchor) {
        final int height = (int)(getHeight() * weight + .5f);
        component.setBounds(0, getHeight() - height, getWidth(), height);
      }
      else if (ToolWindowAnchor.RIGHT == anchor) {
        final int width = (int)(getWidth() * weight + .5f);
        component.setBounds(getWidth() - width, 0, width, getHeight());
      }
      else {
        LOG.error("unknown anchor " + anchor);
      }
    }
  }

  void setStripesOverlayed(boolean stripesOverlayed) {
    myStripesOverlayed = stripesOverlayed;
    updateToolStripesVisibility();
  }

  private static float normalizeWeigh(final float weight) {
    if (weight <= 0) return WindowInfoImpl.DEFAULT_WEIGHT;
    if (weight >= 1) return 1 - WindowInfoImpl.DEFAULT_WEIGHT;
    return weight;
  }
}
