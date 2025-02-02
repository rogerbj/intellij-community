// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.roots.ui.configuration;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.SdkType;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class SdkDetector {
  private static final Logger LOG = Logger.getInstance(SdkDetector.class);

  @NotNull
  public static SdkDetector getInstance() {
    return ApplicationManager.getApplication().getService(SdkDetector.class);
  }

  private final AtomicBoolean myIsRunning = new AtomicBoolean(false);
  private final Object myPublicationLock = new Object();
  private final Set<DetectedSdkListener> myListeners = new HashSet<>();
  private final List<Consumer<DetectedSdkListener>> myDetectedResults = new ArrayList<>();

  /**
   * The callback interface to deliver Sdk search results
   * back to the callee in EDT thread
   */
  public interface DetectedSdkListener {
    void onSdkDetected(@NotNull SdkType type, @Nullable String version, @NotNull String home);
    void onSearchStarted();
    void onSearchCompleted();
  }

  /**
   * Checks and registers the {@param listener} of only is not
   * yet registered. It is assumed the {@param component} is
   * included in the {@link DialogWrapper} so we could implement
   * the correct disposal logic (no SDKs are detected otherwise)
   * <br/>
   * The {@param listener} is populated immediately with all know-by-now
   * detected SDK infos, the listener will be called on the EDT
   * thread to deliver more detected SDKs
   *
   * @param component the requestor component
   * @param listener  the callback interface
   */
  public void getDetectedSdksWithUpdate(@Nullable Project project,
                                        @NotNull Component component,
                                        @NotNull DetectedSdkListener listener) {
    ApplicationManager.getApplication().assertIsDispatchThread();

    DialogWrapper dialogWrapper = DialogWrapper.findInstance(component);
    if (dialogWrapper == null) {
      LOG.warn("Cannot find DialogWrapper parent for the component " + component + ", SDK search is disabled", new RuntimeException());
      return;
    }

    EdtDetectedSdkListener actualListener = new EdtDetectedSdkListener(ModalityState.stateForComponent(component), listener);
    synchronized (myPublicationLock) {
      //skip multiple registrations
      if (!myListeners.add(actualListener)) return;

      //it there is no other listeners, let's refresh
      if (myListeners.size() <= 1 && myIsRunning.compareAndSet(false, true)) {
        myDetectedResults.clear();
        startSdkDetection(project, myMulticaster);
      }

      //deliver everything we have
      myDetectedResults.forEach(result -> result.accept(listener));
    }

    Disposer.register(dialogWrapper.getDisposable(), () -> {
      myListeners.remove(actualListener);
    });
  }

  private final DetectedSdkListener myMulticaster = new DetectedSdkListener() {
    void logEvent(@NotNull Consumer<DetectedSdkListener> e) {
      myDetectedResults.add(e);
      for (DetectedSdkListener listener : myListeners) {
        e.accept(listener);
      }
    }

    @Override
    public void onSearchStarted() {
      synchronized (myPublicationLock) {
        myDetectedResults.clear();
        logEvent(listener -> listener.onSearchStarted());
      }
    }

    @Override
    public void onSdkDetected(@NotNull SdkType type, @Nullable String version, @NotNull String home) {
      synchronized (myPublicationLock) {
        logEvent(listener -> listener.onSdkDetected(type, version, home));
      }
    }

    @Override
    public void onSearchCompleted() {
      synchronized (myPublicationLock) {
        myIsRunning.set(false);
        logEvent(e -> e.onSearchCompleted());
      }
    }
  };

  private static void startSdkDetection(@Nullable Project project, @NotNull DetectedSdkListener callback) {
    Task.Backgroundable task = new Task.Backgroundable(
      project,
      "Detecting SDKs",
      true,
      PerformInBackgroundOption.ALWAYS_BACKGROUND) {

      private void detect(SdkType type, @NotNull ProgressIndicator indicator) {
        try {
          for (String path : new HashSet<>(type.suggestHomePaths())) {
            indicator.checkCanceled();
            if (project != null && project.isDisposed()) indicator.cancel();

            if (path == null) continue;

            try {
              //a sanity check first
              if (!new File(path).exists()) continue;
              if (!type.isValidSdkHome(path)) continue;
            }
            catch (Exception e) {
              LOG.warn("Failed to process detected SDK for " + type + " at " + path + ". " + e.getMessage(), e);
              continue;
            }

            String version;
            try {
              version = type.getVersionString(path);
            }
            catch (Exception e) {
              LOG.warn("Failed to get the detected SDK version for " + type + " at " + path + ". " + e.getMessage(), e);
              continue;
            }

            callback.onSdkDetected(type, version, path);
          }
        }
        catch (ProcessCanceledException e) {
          throw e;
        }
        catch (Exception e) {
          LOG.warn("Failed to detect SDK: " + e.getMessage(), e);
        }
      }

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          callback.onSearchStarted();
          indicator.setIndeterminate(false);
          int item = 0;
          for (SdkType type : SdkType.getAllTypes()) {
            indicator.setFraction((float)item++ / SdkType.getAllTypes().length);
            indicator.checkCanceled();
            detect(type, indicator);
          }
        } finally {
          callback.onSearchCompleted();
        }
      }
    };

    ProgressManager.getInstance().run(task);
  }

  private static class EdtDetectedSdkListener implements DetectedSdkListener {
    private final ModalityState myState;
    private final DetectedSdkListener myTarget;

    EdtDetectedSdkListener(@NotNull ModalityState state,
                           @NotNull DetectedSdkListener target) {
      myState = state;
      myTarget = target;
    }

    void dispatch(@NotNull Runnable r) {
      ApplicationManager.getApplication().invokeLater(r, myState);
    }

    @Override
    public void onSdkDetected(@NotNull SdkType type, @Nullable String version, @NotNull String home) {
      dispatch(() -> myTarget.onSdkDetected(type, version, home));
    }

    @Override
    public void onSearchStarted() {
      dispatch(() -> myTarget.onSearchStarted());
    }

    @Override
    public void onSearchCompleted() {
      dispatch(() -> myTarget.onSearchCompleted());
    }

    @Override
    public int hashCode() {
      return myTarget.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof EdtDetectedSdkListener && Objects.equals(((EdtDetectedSdkListener)obj).myTarget, myTarget);
    }
  }
}
