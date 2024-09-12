/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio;

import com.google.cloud.hadoop.gcsio.GoogleCloudStorageReadOptions.Fadvise;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.GoogleLogger;

/**
 * Manages the access pattern of object being read from cloud storage. For adaptive fadvise
 * configurations it computes the access pattern based on previous requests.
 */
@VisibleForTesting
class FileAccessPatternManager {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private final StorageResourceId resourceId;
  private final GoogleCloudStorageReadOptions readOptions;
  private boolean isPatternOverriden;
  private boolean randomAccess;
  // keeps track of any backward seek requested in lifecycle of InputStream
  private boolean isBackwardSeekRequested = false;
  // keeps track of any backward seek requested in lifecycle of InputStream
  private boolean isForwardSeekRequested = false;
  private long lastServedIndex = -1;
  // Keeps track of distance between consecutive requests
  private int consecutiveSequentialCount = 0;

  public FileAccessPatternManager(
      StorageResourceId resourceId, GoogleCloudStorageReadOptions readOptions) {
    this.isPatternOverriden = false;
    this.resourceId = resourceId;
    this.readOptions = readOptions;
    this.randomAccess =
        readOptions.getFadvise() == Fadvise.AUTO_RANDOM
            || readOptions.getFadvise() == Fadvise.RANDOM;
  }

  public void updateLastServedIndex(long position) {
    this.lastServedIndex = position;
  }

  public boolean isRandomAccessPattern() {
    return randomAccess;
  }

  public void updateAccessPattern(long currentPosition) {
    if (isPatternOverriden) {
      logger.atFiner().log(
          "Will bypass computing access pattern as it's overriden for resource :%s", resourceId);
      return;
    }
    if (readOptions.getFadvise() == Fadvise.AUTO_RANDOM) {
      if (isBackwardOrForwardSeekNotRequested()) {
        if (isSequentialAccessPattern(currentPosition)) {
          unsetRandomAccess();
        } else if (isRandomAccessPattern(currentPosition)) {
          setRandomAccess();
        }
      }
    } else if (readOptions.getFadvise() == Fadvise.AUTO) {
      if (isRandomAccessPattern(currentPosition)) {
        setRandomAccess();
      }
    }
  }

  /**
   * This provides a way to override the access isRandomPattern, once overridden it will not be
   * recomputed for adaptive fadvise types.
   *
   * @param isRandomPattern, true, to override with random access else false
   */
  public void overrideAccessPattern(boolean isRandomPattern) {
    this.isPatternOverriden = true;
    this.randomAccess = isRandomPattern;
    logger.atInfo().log(
        "Overriding the random access pattern to %s for fadvise:%s for resource: %s ",
        isRandomPattern, readOptions.getFadvise(), resourceId);
  }

  private boolean isSequentialAccessPattern(long currentPosition) {
    if (lastServedIndex != -1) {
      long distance = currentPosition - lastServedIndex;
      if (distance < 0 || distance > readOptions.getInplaceSeekLimit()) {
        consecutiveSequentialCount = 0;
      } else {
        consecutiveSequentialCount++;
      }
    }

    if (!shouldDetectSequentialAccess()) {
      return false;
    }

    if (consecutiveSequentialCount < readOptions.getFadviseRequestTrackCount()) {
      return false;
    }
    logger.atInfo().log(
        "Detected %d consecutive read request within distance threshold %d with fadvise: %s switching to sequential IO for '%s'",
        consecutiveSequentialCount,
        readOptions.getInplaceSeekLimit(),
        readOptions.getFadvise(),
        resourceId);
    return true;
  }

  private boolean isRandomAccessPattern(long currentPosition) {
    if (!shouldDetectRandomAccess()) {
      return false;
    }
    if (lastServedIndex == -1) {
      return false;
    }

    if (currentPosition < lastServedIndex) {
      logger.atFine().log(
          "Detected backward read from %s to %s position, switching to random IO for '%s'",
          lastServedIndex, currentPosition, resourceId);

      isBackwardSeekRequested = true;
      consecutiveSequentialCount = 0;
      return true;
    }
    if (lastServedIndex >= 0
        && lastServedIndex + readOptions.getInplaceSeekLimit() < currentPosition) {
      logger.atFine().log(
          "Detected forward read from %s to %s position over %s threshold,"
              + " switching to random IO for '%s'",
          lastServedIndex, currentPosition, readOptions.getInplaceSeekLimit(), resourceId);
      isForwardSeekRequested = true;
      consecutiveSequentialCount = 0;
      return true;
    }
    return false;
  }

  private boolean isBackwardOrForwardSeekNotRequested() {
    return !isBackwardSeekRequested && !isForwardSeekRequested;
  }

  private boolean shouldDetectSequentialAccess() {
    return randomAccess
        && isBackwardOrForwardSeekNotRequested()
        && consecutiveSequentialCount >= readOptions.getFadviseRequestTrackCount()
        && readOptions.getFadvise() == Fadvise.AUTO_RANDOM;
  }

  private boolean shouldDetectRandomAccess() {
    return (!randomAccess && readOptions.getFadvise() == Fadvise.AUTO)
        || (isBackwardOrForwardSeekNotRequested()
            && readOptions.getFadvise() == Fadvise.AUTO_RANDOM);
  }

  private void setRandomAccess() {
    randomAccess = true;
  }

  private void unsetRandomAccess() {
    randomAccess = false;
  }
}