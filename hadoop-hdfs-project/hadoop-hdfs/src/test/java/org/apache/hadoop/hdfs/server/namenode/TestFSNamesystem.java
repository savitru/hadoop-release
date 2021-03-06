/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY;
import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY;
import org.apache.hadoop.util.FakeTimer;
import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;

import com.google.common.base.Supplier;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.NamenodeRole;
import org.apache.hadoop.hdfs.server.namenode.ha.HAContext;
import org.apache.hadoop.hdfs.server.namenode.ha.HAState;
import org.apache.hadoop.hdfs.server.namenode.snapshot.Snapshot;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.GenericTestUtils.LogCapturer;
import org.apache.log4j.Level;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.internal.util.reflection.Whitebox;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public class TestFSNamesystem {

  @After
  public void cleanUp() {
    FileUtil.fullyDeleteContents(new File(MiniDFSCluster.getBaseDirectory()));
  }

  /**
   * Tests that the namenode edits dirs are gotten with duplicates removed
   */
  @Test
  public void testUniqueEditDirs() throws IOException {
    Configuration config = new Configuration();

    config.set(DFS_NAMENODE_EDITS_DIR_KEY, "file://edits/dir, "
        + "file://edits/dir1,file://edits/dir1"); // overlapping internally

    // getNamespaceEditsDirs removes duplicates
    Collection<URI> editsDirs = FSNamesystem.getNamespaceEditsDirs(config);
    assertEquals(2, editsDirs.size());
  }

  /**
   * Test that FSNamesystem#clear clears all leases.
   */
  @Test
  public void testFSNamespaceClearLeases() throws Exception {
    Configuration conf = new HdfsConfiguration();
    File nameDir = new File(MiniDFSCluster.getBaseDirectory(), "name");
    conf.set(DFS_NAMENODE_NAME_DIR_KEY, nameDir.getAbsolutePath());

    NameNode.initMetrics(conf, NamenodeRole.NAMENODE);
    DFSTestUtil.formatNameNode(conf);
    FSNamesystem fsn = FSNamesystem.loadFromDisk(conf);
    LeaseManager leaseMan = fsn.getLeaseManager();
    leaseMan.addLease("client1", "importantFile");
    assertEquals(1, leaseMan.countLease());
    fsn.clear();
    leaseMan = fsn.getLeaseManager();
    assertEquals(0, leaseMan.countLease());
  }

  @Test
  /**
   * Test that isInStartupSafemode returns true only during startup safemode
   * and not also during low-resource safemode
   */
  public void testStartupSafemode() throws IOException {
    Configuration conf = new Configuration();
    FSImage fsImage = Mockito.mock(FSImage.class);
    FSEditLog fsEditLog = Mockito.mock(FSEditLog.class);
    Mockito.when(fsImage.getEditLog()).thenReturn(fsEditLog);
    FSNamesystem fsn = new FSNamesystem(conf, fsImage);

    fsn.leaveSafeMode();
    assertTrue("After leaving safemode FSNamesystem.isInStartupSafeMode still "
      + "returned true", !fsn.isInStartupSafeMode());
    assertTrue("After leaving safemode FSNamesystem.isInSafeMode still returned"
      + " true", !fsn.isInSafeMode());

    fsn.enterSafeMode(true);
    assertTrue("After entering safemode due to low resources FSNamesystem."
      + "isInStartupSafeMode still returned true", !fsn.isInStartupSafeMode());
    assertTrue("After entering safemode due to low resources FSNamesystem."
      + "isInSafeMode still returned false",  fsn.isInSafeMode());
  }

  @Test
  public void testReplQueuesActiveAfterStartupSafemode() throws IOException, InterruptedException{
    Configuration conf = new Configuration();

    FSEditLog fsEditLog = Mockito.mock(FSEditLog.class);
    FSImage fsImage = Mockito.mock(FSImage.class);
    Mockito.when(fsImage.getEditLog()).thenReturn(fsEditLog);

    FSNamesystem fsNamesystem = new FSNamesystem(conf, fsImage);
    FSNamesystem fsn = Mockito.spy(fsNamesystem);

    //Make shouldPopulaeReplQueues return true
    HAContext haContext = Mockito.mock(HAContext.class);
    HAState haState = Mockito.mock(HAState.class);
    Mockito.when(haContext.getState()).thenReturn(haState);
    Mockito.when(haState.shouldPopulateReplQueues()).thenReturn(true);
    Whitebox.setInternalState(fsn, "haContext", haContext);

    //Make NameNode.getNameNodeMetrics() not return null
    NameNode.initMetrics(conf, NamenodeRole.NAMENODE);

    fsn.enterSafeMode(false);
    assertTrue("FSNamesystem didn't enter safemode", fsn.isInSafeMode());
    assertTrue("Replication queues were being populated during very first "
        + "safemode", !fsn.isPopulatingReplQueues());
    fsn.leaveSafeMode();
    assertTrue("FSNamesystem didn't leave safemode", !fsn.isInSafeMode());
    assertTrue("Replication queues weren't being populated even after leaving "
      + "safemode", fsn.isPopulatingReplQueues());
    fsn.enterSafeMode(false);
    assertTrue("FSNamesystem didn't enter safemode", fsn.isInSafeMode());
    assertTrue("Replication queues weren't being populated after entering "
      + "safemode 2nd time", fsn.isPopulatingReplQueues());
  }
  
  @Test
  public void testFsLockFairness() throws IOException, InterruptedException{
    Configuration conf = new Configuration();

    FSEditLog fsEditLog = Mockito.mock(FSEditLog.class);
    FSImage fsImage = Mockito.mock(FSImage.class);
    Mockito.when(fsImage.getEditLog()).thenReturn(fsEditLog);

    conf.setBoolean("dfs.namenode.fslock.fair", true);
    FSNamesystem fsNamesystem = new FSNamesystem(conf, fsImage);
    assertTrue(fsNamesystem.getFsLockForTests().isFair());
    
    conf.setBoolean("dfs.namenode.fslock.fair", false);
    fsNamesystem = new FSNamesystem(conf, fsImage);
    assertFalse(fsNamesystem.getFsLockForTests().isFair());
  }  
  
  @Test
  public void testFSNamesystemLockCompatibility() {
    FSNamesystemLock rwLock = new FSNamesystemLock(true);

    assertEquals(0, rwLock.getReadHoldCount());
    rwLock.readLock().lock();
    assertEquals(1, rwLock.getReadHoldCount());

    rwLock.readLock().lock();
    assertEquals(2, rwLock.getReadHoldCount());

    rwLock.readLock().unlock();
    assertEquals(1, rwLock.getReadHoldCount());

    rwLock.readLock().unlock();
    assertEquals(0, rwLock.getReadHoldCount());

    assertFalse(rwLock.isWriteLockedByCurrentThread());
    assertEquals(0, rwLock.getWriteHoldCount());
    rwLock.writeLock().lock();
    assertTrue(rwLock.isWriteLockedByCurrentThread());
    assertEquals(1, rwLock.getWriteHoldCount());
    
    rwLock.writeLock().lock();
    assertTrue(rwLock.isWriteLockedByCurrentThread());
    assertEquals(2, rwLock.getWriteHoldCount());

    rwLock.writeLock().unlock();
    assertTrue(rwLock.isWriteLockedByCurrentThread());
    assertEquals(1, rwLock.getWriteHoldCount());

    rwLock.writeLock().unlock();
    assertFalse(rwLock.isWriteLockedByCurrentThread());
    assertEquals(0, rwLock.getWriteHoldCount());
  }

  @Test
  public void testReset() throws Exception {
    Configuration conf = new Configuration();
    FSEditLog fsEditLog = Mockito.mock(FSEditLog.class);
    FSImage fsImage = Mockito.mock(FSImage.class);
    Mockito.when(fsImage.getEditLog()).thenReturn(fsEditLog);
    FSNamesystem fsn = new FSNamesystem(conf, fsImage);
    fsn.imageLoadComplete();
    assertTrue(fsn.isImageLoaded());
    fsn.clear();
    assertFalse(fsn.isImageLoaded());
    final INodeDirectory root = (INodeDirectory) fsn.getFSDirectory()
            .getINode("/");
    assertTrue(root.getChildrenList(Snapshot.CURRENT_STATE_ID).isEmpty());
    fsn.imageLoadComplete();
    assertTrue(fsn.isImageLoaded());
  }

  @Test
  public void testGetEffectiveLayoutVersion() {
    assertEquals(-63,
        FSNamesystem.getEffectiveLayoutVersion(true, -60, -61, -63));
    assertEquals(-61,
        FSNamesystem.getEffectiveLayoutVersion(true, -61, -61, -63));
    assertEquals(-62,
        FSNamesystem.getEffectiveLayoutVersion(true, -62, -61, -63));
    assertEquals(-63,
        FSNamesystem.getEffectiveLayoutVersion(true, -63, -61, -63));
    assertEquals(-63,
        FSNamesystem.getEffectiveLayoutVersion(false, -60, -61, -63));
    assertEquals(-63,
        FSNamesystem.getEffectiveLayoutVersion(false, -61, -61, -63));
    assertEquals(-63,
        FSNamesystem.getEffectiveLayoutVersion(false, -62, -61, -63));
    assertEquals(-63,
        FSNamesystem.getEffectiveLayoutVersion(false, -63, -61, -63));
  }

  @Test
  public void testFSLockGetWaiterCount() throws InterruptedException {
    final int threadCount = 3;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final FSNamesystemLock rwLock = new FSNamesystemLock(true);
    rwLock.writeLock().lock();
    ExecutorService helper = Executors.newFixedThreadPool(threadCount);

    for (int x = 0; x < threadCount; x++) {
      helper.execute(new Runnable() {
        @Override
        public void run() {
          latch.countDown();
          rwLock.readLock().lock();
        }
      });
    }

    latch.await();
    try {
      GenericTestUtils.waitFor(new Supplier<Boolean>() {
        @Override
        public Boolean get() {
          return (threadCount == rwLock.getQueueLength());
        }
      }, 100, 1000);
    } catch (TimeoutException e) {
      fail("Expected number of blocked thread not found");
    }
  }

  /**
   * Test when FSNamesystem write lock is held for a long time,
   * logger will report it.
   */
  @Test(timeout=45000)
  public void testFSWriteLockLongHoldingReport() throws Exception {
    final long writeLockReportingThreshold = 100L;
    final long writeLockSuppressWarningInterval = 10000L;
    Configuration conf = new Configuration();
    conf.setLong(DFSConfigKeys.DFS_NAMENODE_WRITE_LOCK_REPORTING_THRESHOLD_MS_KEY,
        writeLockReportingThreshold);
    conf.setTimeDuration(DFSConfigKeys.DFS_LOCK_SUPPRESS_WARNING_INTERVAL_KEY,
        writeLockSuppressWarningInterval, TimeUnit.MILLISECONDS);
    FSImage fsImage = Mockito.mock(FSImage.class);
    FSEditLog fsEditLog = Mockito.mock(FSEditLog.class);
    Mockito.when(fsImage.getEditLog()).thenReturn(fsEditLog);
    final FSNamesystem fsn = new FSNamesystem(conf, fsImage);

    FakeTimer timer = new FakeTimer();
    fsn.setTimer(timer);
    timer.advance(writeLockSuppressWarningInterval);

    LogCapturer logs = LogCapturer.captureLogs(FSNamesystem.LOG);
    GenericTestUtils.setLogLevel(FSNamesystem.LOG, Level.INFO);

    // Don't report if the write lock is held for a short time
    fsn.writeLock();
    fsn.writeUnlock();
    assertFalse(logs.getOutput().contains(GenericTestUtils.getMethodName()));

    // Report the first write lock warning if it is held for a long time
    fsn.writeLock();
    timer.advance(writeLockReportingThreshold + 10);
    logs.clearOutput();
    fsn.writeUnlock();
    assertTrue(logs.getOutput().contains(GenericTestUtils.getMethodName()));

    // Track but do not Report if the write lock is held (interruptibly) for
    // a long time but time since last report does not exceed the suppress
    // warning interval
    fsn.writeLockInterruptibly();
    timer.advance(writeLockReportingThreshold + 10);
    logs.clearOutput();
    fsn.writeUnlock();
    assertFalse(logs.getOutput().contains(GenericTestUtils.getMethodName()));

    // Track but do not Report if it's held for a long time when re-entering
    // write lock but time since last report does not exceed the suppress
    // warning interval
    fsn.writeLock();
    timer.advance(writeLockReportingThreshold/ 2 + 1);
    fsn.writeLockInterruptibly();
    timer.advance(writeLockReportingThreshold/ 2 + 1);
    fsn.writeLock();
    timer.advance(writeLockReportingThreshold/ 2);
    logs.clearOutput();
    fsn.writeUnlock();
    assertFalse(logs.getOutput().contains(GenericTestUtils.getMethodName()));
    logs.clearOutput();
    fsn.writeUnlock();
    assertFalse(logs.getOutput().contains(GenericTestUtils.getMethodName()));
    logs.clearOutput();
    fsn.writeUnlock();
    assertFalse(logs.getOutput().contains(GenericTestUtils.getMethodName()));

    // Report if it's held for a long time and time since last report exceeds
    // the supress warning interval
    timer.advance(writeLockSuppressWarningInterval);
    fsn.writeLock();
    timer.advance(writeLockReportingThreshold + 100);
    logs.clearOutput();
    fsn.writeUnlock();
    assertTrue(logs.getOutput().contains(GenericTestUtils.getMethodName()));
    assertTrue(logs.getOutput().contains("Number of suppressed write-lock " +
        "reports: 2"));
  }

  /**
   * Test when FSNamesystem read lock is held for a long time,
   * logger will report it.
   */
  @Test(timeout=45000)
  public void testFSReadLockLongHoldingReport() throws Exception {
    final long readLockReportingThreshold = 100L;
    final long readLockSuppressWarningInterval = 10000L;
    final String readLockLogStmt = "FSNamesystem read lock held for ";
    Configuration conf = new Configuration();
    conf.setLong(
        DFSConfigKeys.DFS_NAMENODE_READ_LOCK_REPORTING_THRESHOLD_MS_KEY,
        readLockReportingThreshold);
    conf.setTimeDuration(DFSConfigKeys.DFS_LOCK_SUPPRESS_WARNING_INTERVAL_KEY,
        readLockSuppressWarningInterval, TimeUnit.MILLISECONDS);
    FSImage fsImage = Mockito.mock(FSImage.class);
    FSEditLog fsEditLog = Mockito.mock(FSEditLog.class);
    Mockito.when(fsImage.getEditLog()).thenReturn(fsEditLog);
    final FSNamesystem fsn = new FSNamesystem(conf, fsImage);

    final FakeTimer timer = new FakeTimer();
    fsn.setTimer(timer);
    timer.advance(readLockSuppressWarningInterval);

    LogCapturer logs = LogCapturer.captureLogs(FSNamesystem.LOG);
    GenericTestUtils.setLogLevel(FSNamesystem.LOG, Level.INFO);

    // Don't report if the read lock is held for a short time
    fsn.readLock();
    fsn.readUnlock();
    assertFalse(logs.getOutput().contains(GenericTestUtils.getMethodName()) &&
        logs.getOutput().contains(readLockLogStmt));

    // Report the first read lock warning if it is held for a long time
    fsn.readLock();
    timer.advance(readLockReportingThreshold + 10);
    logs.clearOutput();
    fsn.readUnlock();
    assertTrue(logs.getOutput().contains(GenericTestUtils.getMethodName())
        && logs.getOutput().contains(readLockLogStmt));

    // Track but do not Report if the write lock is held for a long time but
    // time since last report does not exceed the suppress warning interval
    fsn.readLock();
    timer.advance(readLockReportingThreshold + 10);
    logs.clearOutput();
    fsn.readUnlock();
    assertFalse(logs.getOutput().contains(GenericTestUtils.getMethodName())
        && logs.getOutput().contains(readLockLogStmt));

    // Track but do not Report if it's held for a long time when re-entering
    // read lock but time since last report does not exceed the suppress
    // warning interval
    fsn.readLock();
    timer.advance(readLockReportingThreshold / 2 + 1);
    fsn.readLock();
    timer.advance(readLockReportingThreshold / 2 + 1);
    logs.clearOutput();
    fsn.readUnlock();
    assertFalse(logs.getOutput().contains(GenericTestUtils.getMethodName()) ||
        logs.getOutput().contains(readLockLogStmt));
    logs.clearOutput();
    fsn.readUnlock();
    assertFalse(logs.getOutput().contains(GenericTestUtils.getMethodName()) &&
        logs.getOutput().contains(readLockLogStmt));

    // Report if it's held for a long time (and time since last report
    // exceeds the suppress warning interval) while another thread also has the
    // read lock. Let one thread hold the lock long enough to activate an
    // alert, then have another thread grab the read lock to ensure that this
    // doesn't reset the timing.
    timer.advance(readLockSuppressWarningInterval);
    logs.clearOutput();
    final CountDownLatch barrier = new CountDownLatch(1);
    final CountDownLatch barrier2 = new CountDownLatch(1);
    Thread t1 = new Thread() {
      @Override
      public void run() {
        try {
          fsn.readLock();
          timer.advance(readLockReportingThreshold + 1);
          barrier.countDown(); // Allow for t2 to acquire the read lock
          barrier2.await(); // Wait until t2 has the read lock
          fsn.readUnlock();
        } catch (InterruptedException e) {
          fail("Interrupted during testing");
        }
      }
    };
    Thread t2 = new Thread() {
      @Override
      public void run() {
        try {
          barrier.await(); // Wait until t1 finishes sleeping
          fsn.readLock();
          barrier2.countDown(); // Allow for t1 to unlock
          fsn.readUnlock();
        } catch (InterruptedException e) {
          fail("Interrupted during testing");
        }
      }
    };
    t1.start();
    t2.start();
    t1.join();
    t2.join();
    // Look for the differentiating class names in the stack trace
    String stackTracePatternString =
        String.format("INFO.+%s(.+\n){4}\\Q%%s\\E\\.run", readLockLogStmt);
    Pattern t1Pattern = Pattern.compile(
        String.format(stackTracePatternString, t1.getClass().getName()));
    assertTrue(t1Pattern.matcher(logs.getOutput()).find());
    Pattern t2Pattern = Pattern.compile(
        String.format(stackTracePatternString, t2.getClass().getName()));
    assertFalse(t2Pattern.matcher(logs.getOutput()).find());
    assertTrue(logs.getOutput().contains("Number of suppressed read-lock " +
        "reports: 2"));
  }
}
