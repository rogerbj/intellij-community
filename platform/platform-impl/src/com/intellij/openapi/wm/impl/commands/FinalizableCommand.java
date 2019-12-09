// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.wm.impl.commands;

import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * @author Vladimir Kondratyev
 */
public abstract class FinalizableCommand implements Runnable {
  private final Runnable myFinishCallBack;

  public FinalizableCommand(@Nullable Runnable finishCallBack) {
    myFinishCallBack = finishCallBack;
  }

  public final void finish() {
    if (myFinishCallBack != null) {
      myFinishCallBack.run();
    }
  }

  @Nullable
  public BooleanSupplier getExpireCondition() {
    return null;
  }

  public boolean willChangeState() {
    return true;
  }
}
