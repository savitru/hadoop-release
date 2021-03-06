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

package org.apache.hadoop.ipc;

import static org.apache.hadoop.test.MetricsAsserts.assertCounter;
import static org.apache.hadoop.test.MetricsAsserts.assertCounterGt;
import static org.apache.hadoop.test.MetricsAsserts.getLongCounter;
import static org.apache.hadoop.test.MetricsAsserts.getMetrics;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.util.reflection.Whitebox.setInternalState;

import java.io.Closeable;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;

import com.google.common.base.Supplier;
import com.google.protobuf.ServiceException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.io.UTF8;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.io.retry.RetryProxy;
import org.apache.hadoop.ipc.Client.ConnectionId;
import org.apache.hadoop.ipc.Server.Call;
import org.apache.hadoop.metrics2.MetricsRecordBuilder;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.UserGroupInformation.AuthenticationMethod;
import org.apache.hadoop.security.authorize.AuthorizationException;
import org.apache.hadoop.security.authorize.PolicyProvider;
import org.apache.hadoop.security.authorize.Service;
import org.apache.hadoop.security.token.SecretManager;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.MetricsAsserts;
import org.apache.hadoop.test.MockitoUtil;
import org.apache.log4j.Level;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.internal.util.reflection.Whitebox;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import org.mockito.Mockito;
import org.mockito.internal.util.reflection.Whitebox;

/** Unit tests for RPC. */
@SuppressWarnings("deprecation")
public class TestRPC {
  private static final String ADDRESS = "0.0.0.0";

  public static final Log LOG =
    LogFactory.getLog(TestRPC.class);
  
  private static Configuration conf;
  
  @Before
  public void setupConf() {
    conf = new Configuration();
    conf.setClass("rpc.engine." + StoppedProtocol.class.getName(),
        StoppedRpcEngine.class, RpcEngine.class);
    UserGroupInformation.setConfiguration(conf);
  }

  int datasize = 1024*100;
  int numThreads = 50;

  public interface TestProtocol extends VersionedProtocol {
    public static final long versionID = 1L;
    
    void ping() throws IOException;
    void slowPing(boolean shouldSlow) throws IOException;
    void sleep(long delay) throws IOException, InterruptedException;
    String echo(String value) throws IOException;
    String[] echo(String[] value) throws IOException;
    Writable echo(Writable value) throws IOException;
    int add(int v1, int v2) throws IOException;
    int add(int[] values) throws IOException;
    int error() throws IOException;
    void testServerGet() throws IOException;
    int[] exchange(int[] values) throws IOException;
    
    DescriptorProtos.EnumDescriptorProto exchangeProto(
        DescriptorProtos.EnumDescriptorProto arg);
  }

  public static class TestImpl implements TestProtocol {
    int fastPingCounter = 0;
    
    @Override
    public long getProtocolVersion(String protocol, long clientVersion) {
      return TestProtocol.versionID;
    }
    
    @Override
    public ProtocolSignature getProtocolSignature(String protocol, long clientVersion,
        int hashcode) {
      return new ProtocolSignature(TestProtocol.versionID, null);
    }
    
    @Override
    public void ping() {}

    @Override
    public synchronized void slowPing(boolean shouldSlow) {
      if (shouldSlow) {
        while (fastPingCounter < 2) {
          try {
          wait();  // slow response until two fast pings happened
          } catch (InterruptedException ignored) {}
        }
        fastPingCounter -= 2;
      } else {
        fastPingCounter++;
        notify();
      }
    }
    
    @Override
    public void sleep(long delay) throws InterruptedException {
      Thread.sleep(delay);
    }
    
    @Override
    public String echo(String value) throws IOException { return value; }

    @Override
    public String[] echo(String[] values) throws IOException { return values; }

    @Override
    public Writable echo(Writable writable) {
      return writable;
    }
    @Override
    public int add(int v1, int v2) {
      return v1 + v2;
    }

    @Override
    public int add(int[] values) {
      int sum = 0;
      for (int i = 0; i < values.length; i++) {
        sum += values[i];
      }
      return sum;
    }

    @Override
    public int error() throws IOException {
      throw new IOException("bobo");
    }

    @Override
    public void testServerGet() throws IOException {
      if (!(Server.get() instanceof RPC.Server)) {
        throw new IOException("Server.get() failed");
      }
    }

    @Override
    public int[] exchange(int[] values) {
      for (int i = 0; i < values.length; i++) {
        values[i] = i;
      }
      return values;
    }

    @Override
    public EnumDescriptorProto exchangeProto(EnumDescriptorProto arg) {
      return arg;
    }
  }

  //
  // an object that does a bunch of transactions
  //
  static class Transactions implements Runnable {
    int datasize;
    TestProtocol proxy;

    Transactions(TestProtocol proxy, int datasize) {
      this.proxy = proxy;
      this.datasize = datasize;
    }

    // do two RPC that transfers data.
    @Override
    public void run() {
      int[] indata = new int[datasize];
      int[] outdata = null;
      int val = 0;
      try {
        outdata = proxy.exchange(indata);
        val = proxy.add(1,2);
      } catch (IOException e) {
        assertTrue("Exception from RPC exchange() "  + e, false);
      }
      assertEquals(indata.length, outdata.length);
      assertEquals(3, val);
      for (int i = 0; i < outdata.length; i++) {
        assertEquals(outdata[i], i);
      }
    }
  }

  //
  // A class that does an RPC but does not read its response.
  //
  static class SlowRPC implements Runnable {
    private TestProtocol proxy;
    private volatile boolean done;
   
    SlowRPC(TestProtocol proxy) {
      this.proxy = proxy;
      done = false;
    }

    boolean isDone() {
      return done;
    }

    @Override
    public void run() {
      try {
        proxy.slowPing(true);   // this would hang until two fast pings happened
        done = true;
      } catch (IOException e) {
        assertTrue("SlowRPC ping exception " + e, false);
      }
    }
  }
  
  /**
   * A basic interface for testing client-side RPC resource cleanup.
   */
  private static interface StoppedProtocol {
    long versionID = 0;

    public void stop();
  }
  
  /**
   * A class used for testing cleanup of client side RPC resources.
   */
  private static class StoppedRpcEngine implements RpcEngine {

    @Override
    public <T> ProtocolProxy<T> getProxy(Class<T> protocol, long clientVersion,
        InetSocketAddress addr, UserGroupInformation ticket, Configuration conf,
        SocketFactory factory, int rpcTimeout, RetryPolicy connectionRetryPolicy
        ) throws IOException {
      return getProxy(protocol, clientVersion, addr, ticket, conf, factory,
        rpcTimeout, connectionRetryPolicy, null);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> ProtocolProxy<T> getProxy(Class<T> protocol, long clientVersion,
        InetSocketAddress addr, UserGroupInformation ticket, Configuration conf,
        SocketFactory factory, int rpcTimeout,
        RetryPolicy connectionRetryPolicy, AtomicBoolean fallbackToSimpleAuth
        ) throws IOException {
      T proxy = (T) Proxy.newProxyInstance(protocol.getClassLoader(),
              new Class[] { protocol }, new StoppedInvocationHandler());
      return new ProtocolProxy<T>(protocol, proxy, false);
    }

    @Override
    public org.apache.hadoop.ipc.RPC.Server getServer(Class<?> protocol,
        Object instance, String bindAddress, int port, int numHandlers,
        int numReaders, int queueSizePerHandler, boolean verbose, Configuration conf,
        SecretManager<? extends TokenIdentifier> secretManager, 
        String portRangeConfig) throws IOException {
      return null;
    }

    @Override
    public ProtocolProxy<ProtocolMetaInfoPB> getProtocolMetaInfoProxy(
        ConnectionId connId, Configuration conf, SocketFactory factory)
        throws IOException {
      throw new UnsupportedOperationException("This proxy is not supported");
    }
  }

  /**
   * An invocation handler which does nothing when invoking methods, and just
   * counts the number of times close() is called.
   */
  private static class StoppedInvocationHandler
      implements InvocationHandler, Closeable {
    
    private int closeCalled = 0;

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
        throws Throwable {
          return null;
    }

    @Override
    public void close() throws IOException {
      closeCalled++;
    }
    
    public int getCloseCalled() {
      return closeCalled;
    }
    
  }
  
  @Test
  public void testConfRpc() throws IOException {
    Server server = new RPC.Builder(conf).setProtocol(TestProtocol.class)
        .setInstance(new TestImpl()).setBindAddress(ADDRESS).setPort(0)
        .setNumHandlers(1).setVerbose(false).build();
    // Just one handler
    int confQ = conf.getInt(
              CommonConfigurationKeys.IPC_SERVER_HANDLER_QUEUE_SIZE_KEY,
              CommonConfigurationKeys.IPC_SERVER_HANDLER_QUEUE_SIZE_DEFAULT);
    assertEquals(confQ, server.getMaxQueueSize());

    int confReaders = conf.getInt(
              CommonConfigurationKeys.IPC_SERVER_RPC_READ_THREADS_KEY,
              CommonConfigurationKeys.IPC_SERVER_RPC_READ_THREADS_DEFAULT);
    assertEquals(confReaders, server.getNumReaders());
    server.stop();
    
    server = new RPC.Builder(conf).setProtocol(TestProtocol.class)
        .setInstance(new TestImpl()).setBindAddress(ADDRESS).setPort(0)
        .setNumHandlers(1).setnumReaders(3).setQueueSizePerHandler(200)
        .setVerbose(false).build();        
        
    assertEquals(3, server.getNumReaders());
    assertEquals(200, server.getMaxQueueSize());
    server.stop();    

    server = new RPC.Builder(conf).setProtocol(TestProtocol.class)
        .setInstance(new TestImpl()).setBindAddress(ADDRESS).setPort(0)
        .setQueueSizePerHandler(10).setNumHandlers(2)
        .setVerbose(false).build();
    assertEquals(2 * 10, server.getMaxQueueSize());
    server.stop();
  }

  @Test
  public void testProxyAddress() throws IOException {
    Server server = new RPC.Builder(conf).setProtocol(TestProtocol.class)
        .setInstance(new TestImpl()).setBindAddress(ADDRESS).setPort(0).build();
    TestProtocol proxy = null;
    
    try {
      server.start();
      InetSocketAddress addr = NetUtils.getConnectAddress(server);

      // create a client
      proxy = RPC.getProxy(TestProtocol.class, TestProtocol.versionID, addr, conf);
      
      assertEquals(addr, RPC.getServerAddress(proxy));
    } finally {
      server.stop();
      if (proxy != null) {
        RPC.stopProxy(proxy);
      }
    }
  }

  @Test
  public void testSlowRpc() throws IOException {
    System.out.println("Testing Slow RPC");
    // create a server with two handlers
    Server server = new RPC.Builder(conf).setProtocol(TestProtocol.class)
        .setInstance(new TestImpl()).setBindAddress(ADDRESS).setPort(0)
        .setNumHandlers(2).setVerbose(false).build();
    
    TestProtocol proxy = null;
    
    try {
    server.start();

    InetSocketAddress addr = NetUtils.getConnectAddress(server);

    // create a client
    proxy = RPC.getProxy(TestProtocol.class, TestProtocol.versionID, addr, conf);

    SlowRPC slowrpc = new SlowRPC(proxy);
    Thread thread = new Thread(slowrpc, "SlowRPC");
    thread.start(); // send a slow RPC, which won't return until two fast pings
    assertTrue("Slow RPC should not have finished1.", !slowrpc.isDone());

    proxy.slowPing(false); // first fast ping
    
    // verify that the first RPC is still stuck
    assertTrue("Slow RPC should not have finished2.", !slowrpc.isDone());

    proxy.slowPing(false); // second fast ping
    
    // Now the slow ping should be able to be executed
    while (!slowrpc.isDone()) {
      System.out.println("Waiting for slow RPC to get done.");
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {}
    }
    } finally {
      server.stop();
      if (proxy != null) {
        RPC.stopProxy(proxy);
      }
      System.out.println("Down slow rpc testing");
    }
  }
  
  @Test
  public void testCalls() throws IOException {
    testCallsInternal(conf);
  }
  
  private void testCallsInternal(Configuration conf) throws IOException {
    Server server = new RPC.Builder(conf).setProtocol(TestProtocol.class)
        .setInstance(new TestImpl()).setBindAddress(ADDRESS).setPort(0).build();
    TestProtocol proxy = null;
    try {
    server.start();

    InetSocketAddress addr = NetUtils.getConnectAddress(server);
    proxy = RPC.getProxy(TestProtocol.class, TestProtocol.versionID, addr, conf);
      
    proxy.ping();

    String stringResult = proxy.echo("foo");
    assertEquals(stringResult, "foo");

    stringResult = proxy.echo((String)null);
    assertEquals(stringResult, null);
    
    // Check rpcMetrics 
    MetricsRecordBuilder rb = getMetrics(server.rpcMetrics.name());
    assertCounter("RpcProcessingTimeNumOps", 3L, rb);
    assertCounterGt("SentBytes", 0L, rb);
    assertCounterGt("ReceivedBytes", 0L, rb);
    
    // Number of calls to echo method should be 2
    rb = getMetrics(server.rpcDetailedMetrics.name());
    assertCounter("EchoNumOps", 2L, rb);
    
    // Number of calls to ping method should be 1
    assertCounter("PingNumOps", 1L, rb);
    
    String[] stringResults = proxy.echo(new String[]{"foo","bar"});
    assertTrue(Arrays.equals(stringResults, new String[]{"foo","bar"}));

    stringResults = proxy.echo((String[])null);
    assertTrue(Arrays.equals(stringResults, null));

    UTF8 utf8Result = (UTF8)proxy.echo(new UTF8("hello world"));
    assertEquals(new UTF8("hello world"), utf8Result );

    utf8Result = (UTF8)proxy.echo((UTF8)null);
    assertEquals(null, utf8Result);

    int intResult = proxy.add(1, 2);
    assertEquals(intResult, 3);

    intResult = proxy.add(new int[] {1, 2});
    assertEquals(intResult, 3);

    // Test protobufs
    EnumDescriptorProto sendProto =
      EnumDescriptorProto.newBuilder().setName("test").build();
    EnumDescriptorProto retProto = proxy.exchangeProto(sendProto);
    assertEquals(sendProto, retProto);
    assertNotSame(sendProto, retProto);

    boolean caught = false;
    try {
      proxy.error();
    } catch (IOException e) {
      if(LOG.isDebugEnabled()) {
        LOG.debug("Caught " + e);
      }
      caught = true;
    }
    assertTrue(caught);
    rb = getMetrics(server.rpcDetailedMetrics.name());
    assertCounter("IOExceptionNumOps", 1L, rb);

    proxy.testServerGet();

    // create multiple threads and make them do large data transfers
    System.out.println("Starting multi-threaded RPC test...");
    server.setSocketSendBufSize(1024);
    Thread threadId[] = new Thread[numThreads];
    for (int i = 0; i < numThreads; i++) {
      Transactions trans = new Transactions(proxy, datasize);
      threadId[i] = new Thread(trans, "TransactionThread-" + i);
      threadId[i].start();
    }

    // wait for all transactions to get over
    System.out.println("Waiting for all threads to finish RPCs...");
    for (int i = 0; i < numThreads; i++) {
      try {
        threadId[i].join();
      } catch (InterruptedException e) {
        i--;      // retry
      }
    }

    } finally {
      server.stop();
      if(proxy!=null) RPC.stopProxy(proxy);
    }
  }
  
  @Test
  public void testStandaloneClient() throws IOException {
    try {
      TestProtocol proxy = RPC.waitForProxy(TestProtocol.class,
        TestProtocol.versionID, new InetSocketAddress(ADDRESS, 20), conf, 15000L);
      proxy.echo("");
      fail("We should not have reached here");
    } catch (ConnectException ioe) {
      //this is what we expected
    }
  }
  
  private static final String ACL_CONFIG = "test.protocol.acl";
  
  private static class TestPolicyProvider extends PolicyProvider {

    @Override
    public Service[] getServices() {
      return new Service[] { new Service(ACL_CONFIG, TestProtocol.class) };
    }
    
  }
  
  private void doRPCs(Configuration conf, boolean expectFailure) throws IOException {
    Server server = new RPC.Builder(conf).setProtocol(TestProtocol.class)
        .setInstance(new TestImpl()).setBindAddress(ADDRESS).setPort(0)
        .setNumHandlers(5).setVerbose(true).build();

    server.refreshServiceAcl(conf, new TestPolicyProvider());

    TestProtocol proxy = null;

    server.start();

    InetSocketAddress addr = NetUtils.getConnectAddress(server);
    
    try {
      proxy = RPC.getProxy(TestProtocol.class, TestProtocol.versionID, addr, conf);
      proxy.ping();

      if (expectFailure) {
        fail("Expect RPC.getProxy to fail with AuthorizationException!");
      }
    } catch (RemoteException e) {
      if (expectFailure) {
        assertTrue(e.unwrapRemoteException() instanceof AuthorizationException);
      } else {
        throw e;
      }
    } finally {
      server.stop();
      if (proxy != null) {
        RPC.stopProxy(proxy);
      }
      MetricsRecordBuilder rb = getMetrics(server.rpcMetrics.name());
      if (expectFailure) {
        assertCounter("RpcAuthorizationFailures", 1L, rb);
      } else {
        assertCounter("RpcAuthorizationSuccesses", 1L, rb);
      }
      //since we don't have authentication turned ON, we should see 
      // 0 for the authentication successes and 0 for failure
      assertCounter("RpcAuthenticationFailures", 0L, rb);
      assertCounter("RpcAuthenticationSuccesses", 0L, rb);
    }
  }
  
  @Test
  public void testServerAddress() throws IOException {
    Server server = new RPC.Builder(conf).setProtocol(TestProtocol.class)
        .setInstance(new TestImpl()).setBindAddress(ADDRESS).setPort(0)
        .setNumHandlers(5).setVerbose(true).build();
    InetSocketAddress bindAddr = null;
    try {
      bindAddr = NetUtils.getConnectAddress(server);
    } finally {
      server.stop();
    }
    assertEquals(InetAddress.getLocalHost(), bindAddr.getAddress());
  }

  @Test
  public void testAuthorization() throws IOException {
    Configuration conf = new Configuration();
    conf.setBoolean(CommonConfigurationKeys.HADOOP_SECURITY_AUTHORIZATION,
        true);

    // Expect to succeed
    conf.set(ACL_CONFIG, "*");
    doRPCs(conf, false);

    // Reset authorization to expect failure
    conf.set(ACL_CONFIG, "invalid invalid");
    doRPCs(conf, true);

    conf.setInt(CommonConfigurationKeys.IPC_SERVER_RPC_READ_THREADS_KEY, 2);
    // Expect to succeed
    conf.set(ACL_CONFIG, "*");
    doRPCs(conf, false);

    // Reset authorization to expect failure
    conf.set(ACL_CONFIG, "invalid invalid");
    doRPCs(conf, true);
  }

  /**
   * Switch off setting socketTimeout values on RPC sockets.
   * Verify that RPC calls still work ok.
   */
  public void testNoPings() throws IOException {
    Configuration conf = new Configuration();
    
    conf.setBoolean("ipc.client.ping", false);
    new TestRPC().testCallsInternal(conf);
    
    conf.setInt(CommonConfigurationKeys.IPC_SERVER_RPC_READ_THREADS_KEY, 2);
    new TestRPC().testCallsInternal(conf);
  }

  /**
   * Test stopping a non-registered proxy
   * @throws IOException
   */
  @Test(expected=HadoopIllegalArgumentException.class)
  public void testStopNonRegisteredProxy() throws IOException {
    RPC.stopProxy(null);
  }

  /**
   * Test that the mockProtocol helper returns mock proxies that can
   * be stopped without error.
   */
  @Test
  public void testStopMockObject() throws IOException {
    RPC.stopProxy(MockitoUtil.mockProtocol(TestProtocol.class));
  }

  @Test
  public void testStopProxy() throws IOException {
    StoppedProtocol proxy = RPC.getProxy(StoppedProtocol.class,
        StoppedProtocol.versionID, null, conf);
    StoppedInvocationHandler invocationHandler = (StoppedInvocationHandler)
        Proxy.getInvocationHandler(proxy);
    assertEquals(0, invocationHandler.getCloseCalled());
    RPC.stopProxy(proxy);
    assertEquals(1, invocationHandler.getCloseCalled());
  }

  @Test
  public void testWrappedStopProxy() throws IOException {
    StoppedProtocol wrappedProxy = RPC.getProxy(StoppedProtocol.class,
        StoppedProtocol.versionID, null, conf);
    StoppedInvocationHandler invocationHandler = (StoppedInvocationHandler)
        Proxy.getInvocationHandler(wrappedProxy);

    StoppedProtocol proxy = (StoppedProtocol) RetryProxy.create(StoppedProtocol.class,
        wrappedProxy, RetryPolicies.RETRY_FOREVER);

    assertEquals(0, invocationHandler.getCloseCalled());
    RPC.stopProxy(proxy);
    assertEquals(1, invocationHandler.getCloseCalled());
  }

  @Test
  public void testErrorMsgForInsecureClient() throws IOException {
    Configuration serverConf = new Configuration(conf);
    SecurityUtil.setAuthenticationMethod(AuthenticationMethod.KERBEROS,
                                         serverConf);
    UserGroupInformation.setConfiguration(serverConf);

    final Server server = new RPC.Builder(serverConf).setProtocol(TestProtocol.class)
        .setInstance(new TestImpl()).setBindAddress(ADDRESS).setPort(0)
        .setNumHandlers(5).setVerbose(true).build();
    server.start();

    UserGroupInformation.setConfiguration(conf);
    boolean succeeded = false;
    final InetSocketAddress addr = NetUtils.getConnectAddress(server);
    TestProtocol proxy = null;
    try {
      proxy = RPC.getProxy(TestProtocol.class, TestProtocol.versionID, addr, conf);
      proxy.echo("");
    } catch (RemoteException e) {
      LOG.info("LOGGING MESSAGE: " + e.getLocalizedMessage());
      assertTrue(e.unwrapRemoteException() instanceof AccessControlException);
      succeeded = true;
    } finally {
      server.stop();
      if (proxy != null) {
        RPC.stopProxy(proxy);
      }
    }
    assertTrue(succeeded);

    conf.setInt(CommonConfigurationKeys.IPC_SERVER_RPC_READ_THREADS_KEY, 2);

    UserGroupInformation.setConfiguration(serverConf);
    final Server multiServer = new RPC.Builder(serverConf)
        .setProtocol(TestProtocol.class).setInstance(new TestImpl())
        .setBindAddress(ADDRESS).setPort(0).setNumHandlers(5).setVerbose(true)
        .build();
    multiServer.start();
    succeeded = false;
    final InetSocketAddress mulitServerAddr =
                      NetUtils.getConnectAddress(multiServer);
    proxy = null;
    try {
      UserGroupInformation.setConfiguration(conf);
      proxy = RPC.getProxy(TestProtocol.class,
          TestProtocol.versionID, mulitServerAddr, conf);
      proxy.echo("");
    } catch (RemoteException e) {
      LOG.info("LOGGING MESSAGE: " + e.getLocalizedMessage());
      assertTrue(e.unwrapRemoteException() instanceof AccessControlException);
      succeeded = true;
    } finally {
      multiServer.stop();
      if (proxy != null) {
        RPC.stopProxy(proxy);
      }
    }
    assertTrue(succeeded);
  }

  /**
   * Count the number of threads that have a stack frame containing
   * the given string
   */
  private static int countThreads(String search) {
    ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    int count = 0;
    ThreadInfo[] infos = threadBean.getThreadInfo(threadBean.getAllThreadIds(), 20);
    for (ThreadInfo info : infos) {
      if (info == null) continue;
      for (StackTraceElement elem : info.getStackTrace()) {
        if (elem.getClassName().contains(search)) {
          count++;
          break;
        }
      }
    }
    return count;
  }

  /**
   * Test that server.stop() properly stops all threads
   */
  @Test
  public void testStopsAllThreads() throws IOException, InterruptedException {
    int threadsBefore = countThreads("Server$Listener$Reader");
    assertEquals("Expect no Reader threads running before test",
      0, threadsBefore);

    final Server server = new RPC.Builder(conf).setProtocol(TestProtocol.class)
        .setInstance(new TestImpl()).setBindAddress(ADDRESS).setPort(0)
        .setNumHandlers(5).setVerbose(true).build();
    server.start();
    try {
      // Wait for at least one reader thread to start
      int threadsRunning = 0;
      long totalSleepTime = 0;
      do {
        totalSleepTime += 10;
        Thread.sleep(10);
        threadsRunning = countThreads("Server$Listener$Reader");
      } while (threadsRunning == 0 && totalSleepTime < 5000);

      // Validate that at least one thread started (we didn't timeout)
      threadsRunning = countThreads("Server$Listener$Reader");
      assertTrue(threadsRunning > 0);
    } finally {
      server.stop();
    }
    int threadsAfter = countThreads("Server$Listener$Reader");
    assertEquals("Expect no Reader threads left running after test",
      0, threadsAfter);
  }
  
  @Test
  public void testRPCBuilder() throws IOException {
    // Test mandatory field conf
    try {
      new RPC.Builder(null).setProtocol(TestProtocol.class)
          .setInstance(new TestImpl()).setBindAddress(ADDRESS).setPort(0)
          .setNumHandlers(5).setVerbose(true).build();
      fail("Didn't throw HadoopIllegalArgumentException");
    } catch (Exception e) {
      if (!(e instanceof HadoopIllegalArgumentException)) {
        fail("Expecting HadoopIllegalArgumentException but caught " + e);
      }
    }
    // Test mandatory field protocol
    try {
      new RPC.Builder(conf).setInstance(new TestImpl()).setBindAddress(ADDRESS)
          .setPort(0).setNumHandlers(5).setVerbose(true).build();
      fail("Didn't throw HadoopIllegalArgumentException");
    } catch (Exception e) {
      if (!(e instanceof HadoopIllegalArgumentException)) {
        fail("Expecting HadoopIllegalArgumentException but caught " + e);
      }
    }
    // Test mandatory field instance
    try {
      new RPC.Builder(conf).setProtocol(TestProtocol.class)
          .setBindAddress(ADDRESS).setPort(0).setNumHandlers(5)
          .setVerbose(true).build();
      fail("Didn't throw HadoopIllegalArgumentException");
    } catch (Exception e) {
      if (!(e instanceof HadoopIllegalArgumentException)) {
        fail("Expecting HadoopIllegalArgumentException but caught " + e);
      }
    }
  }
  
  @Test(timeout=90000)
  public void testRPCInterruptedSimple() throws IOException {
    final Configuration conf = new Configuration();
    Server server = new RPC.Builder(conf).setProtocol(TestProtocol.class)
        .setInstance(new TestImpl()).setBindAddress(ADDRESS)
        .setPort(0).setNumHandlers(5).setVerbose(true)
        .setSecretManager(null).build();
    
    server.start();
    InetSocketAddress addr = NetUtils.getConnectAddress(server);

    final TestProtocol proxy = RPC.getProxy(
        TestProtocol.class, TestProtocol.versionID, addr, conf);
    // Connect to the server
    proxy.ping();
    // Interrupt self, try another call
    Thread.currentThread().interrupt();
    try {
      proxy.ping();
      fail("Interruption did not cause IPC to fail");
    } catch (IOException ioe) {
      if (ioe.toString().contains("InterruptedException") ||
          ioe instanceof InterruptedIOException) {
        // clear interrupt status for future tests
        Thread.interrupted();
        return;
      }
      throw ioe;
    } finally {
      server.stop();
    }
  }
  
  @Test(timeout=30000)
  public void testRPCInterrupted() throws IOException, InterruptedException {
    final Configuration conf = new Configuration();
    Server server = new RPC.Builder(conf).setProtocol(TestProtocol.class)
        .setInstance(new TestImpl()).setBindAddress(ADDRESS)
        .setPort(0).setNumHandlers(5).setVerbose(true)
        .setSecretManager(null).build();

    server.start();

    int numConcurrentRPC = 200;
    InetSocketAddress addr = NetUtils.getConnectAddress(server);
    final CyclicBarrier barrier = new CyclicBarrier(numConcurrentRPC);
    final CountDownLatch latch = new CountDownLatch(numConcurrentRPC);
    final AtomicBoolean leaderRunning = new AtomicBoolean(true);
    final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
    Thread leaderThread = null;
    
    for (int i = 0; i < numConcurrentRPC; i++) {
      final int num = i;
      final TestProtocol proxy = RPC.getProxy(
      TestProtocol.class, TestProtocol.versionID, addr, conf);
      Thread rpcThread = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            barrier.await();
            while (num == 0 || leaderRunning.get()) {
              proxy.slowPing(false);
            }

            proxy.slowPing(false);
          } catch (Exception e) {
            if (num == 0) {
              leaderRunning.set(false);
            } else {
              error.set(e);
            }

            LOG.error(e);
          } finally {
            latch.countDown();
          }
        }
      });
      rpcThread.start();

      if (leaderThread == null) {
       leaderThread = rpcThread;
      }
    }
    // let threads get past the barrier
    Thread.sleep(1000);
    // stop a single thread
    while (leaderRunning.get()) {
      leaderThread.interrupt();
    }
    
    latch.await();
    
    // should not cause any other thread to get an error
    assertTrue("rpc got exception " + error.get(), error.get() == null);
    server.stop();
  }

  @Test
  public void testConnectionPing() throws Exception {
    Configuration conf = new Configuration();
    int pingInterval = 50;
    conf.setBoolean(CommonConfigurationKeys.IPC_CLIENT_PING_KEY, true);
    conf.setInt(CommonConfigurationKeys.IPC_PING_INTERVAL_KEY, pingInterval);
    final Server server = new RPC.Builder(conf)
        .setProtocol(TestProtocol.class).setInstance(new TestImpl())
        .setBindAddress(ADDRESS).setPort(0).setNumHandlers(5).setVerbose(true)
        .build();
    server.start();

    final TestProtocol proxy = RPC.getProxy(TestProtocol.class,
        TestProtocol.versionID, server.getListenerAddress(), conf);
    try {
      // this call will throw exception if server couldn't decode the ping
      proxy.sleep(pingInterval*4);
    } finally {
      if (proxy != null) RPC.stopProxy(proxy);
      server.stop();
    }
  }

  @Test
  public void testRpcMetrics() throws Exception {
    Configuration configuration = new Configuration();
    final int interval = 1;
    configuration.setBoolean(CommonConfigurationKeys.
        RPC_METRICS_QUANTILE_ENABLE, true);
    configuration.set(CommonConfigurationKeys.
        RPC_METRICS_PERCENTILES_INTERVALS_KEY, "" + interval);
    final Server server = new RPC.Builder(configuration)
        .setProtocol(TestProtocol.class).setInstance(new TestImpl())
        .setBindAddress(ADDRESS).setPort(0).setNumHandlers(5).setVerbose(true)
        .build();
    server.start();
    String testUser = "testUser";
    UserGroupInformation anotherUser =
        UserGroupInformation.createRemoteUser(testUser);
    TestProtocol proxy2 =
        anotherUser.doAs(new PrivilegedAction<TestProtocol>() {
          public TestProtocol run() {
            try {
              return RPC.getProxy(TestProtocol.class, 0,
                  server.getListenerAddress(), conf);
            } catch (IOException e) {
              e.printStackTrace();
            }
            return null;
          }
        });
    final TestProtocol proxy = RPC.getProxy(TestProtocol.class,
        TestProtocol.versionID, server.getListenerAddress(), configuration);
    try {
      for (int i=0; i<1000; i++) {
        proxy.ping();
        proxy.echo("" + i);
        proxy2.echo("" + i);
      }
      MetricsRecordBuilder rpcMetrics =
          getMetrics(server.getRpcMetrics().name());
      assertTrue("Expected non-zero rpc queue time",
          getLongCounter("RpcQueueTimeNumOps", rpcMetrics) > 0);
      assertTrue("Expected non-zero rpc processing time",
          getLongCounter("RpcProcessingTimeNumOps", rpcMetrics) > 0);
      MetricsAsserts.assertQuantileGauges("RpcQueueTime" + interval + "s",
          rpcMetrics);
      MetricsAsserts.assertQuantileGauges("RpcProcessingTime" + interval + "s",
          rpcMetrics);
      String actualUserVsCon = MetricsAsserts
          .getStringMetric("NumOpenConnectionsPerUser", rpcMetrics);
      String proxyUser =
          UserGroupInformation.getCurrentUser().getShortUserName();
      assertTrue(actualUserVsCon.contains("\"" + proxyUser + "\":1"));
      assertTrue(actualUserVsCon.contains("\"" + testUser + "\":1"));
    } finally {
      if (proxy != null) {
        RPC.stopProxy(proxy);
      }
      if (proxy2 != null) {
        RPC.stopProxy(proxy2);
      }
      server.stop();
    }
  }

  /**
   *  Verify the RPC server can shutdown properly when callQueue is full.
   */
  @Test (timeout=30000)
  public void testRPCServerShutdown() throws Exception {
    final int numClients = 3;
    final List<Future<Void>> res = new ArrayList<Future<Void>>();
    final ExecutorService executorService =
        Executors.newFixedThreadPool(numClients);
    final Configuration conf = new Configuration();
    conf.setInt(CommonConfigurationKeys.IPC_CLIENT_CONNECT_MAX_RETRIES_KEY, 0);
    final Server server = new RPC.Builder(conf)
        .setProtocol(TestProtocol.class).setInstance(new TestImpl())
        .setBindAddress(ADDRESS).setPort(0)
        .setQueueSizePerHandler(1).setNumHandlers(1).setVerbose(true)
        .build();
    server.start();

    final TestProtocol proxy =
        RPC.getProxy(TestProtocol.class, TestProtocol.versionID,
            NetUtils.getConnectAddress(server), conf);
    try {
      // start a sleep RPC call to consume the only handler thread.
      // Start another sleep RPC call to make callQueue full.
      // Start another sleep RPC call to make reader thread block on CallQueue.
      for (int i = 0; i < numClients; i++) {
        res.add(executorService.submit(
            new Callable<Void>() {
              @Override
              public Void call() throws IOException, InterruptedException {
                proxy.sleep(100000);
                return null;
              }
            }));
      }
      while (server.getCallQueueLen() != 1
          || countThreads(CallQueueManager.class.getName()) != 1
          || countThreads(TestImpl.class.getName()) != 1) {
        Thread.sleep(100);
      }
    } finally {
      try {
        server.stop();
        assertEquals("Not enough clients", numClients, res.size());
        for (Future<Void> f : res) {
          try {
            f.get();
            fail("Future get should not return");
          } catch (ExecutionException e) {
            assertTrue("Unexpected exception: " + e,
                e.getCause() instanceof IOException);
            LOG.info("Expected exception", e.getCause());
          }
        }
      } finally {
        RPC.stopProxy(proxy);
        executorService.shutdown();
      }
    }
  }

  /**
   *  Test RPC backoff by queue full.
   */
  @Test (timeout=30000)
  public void testClientBackOff() throws Exception {
    boolean succeeded = false;
    final int numClients = 2;
    final List<Future<Void>> res = new ArrayList<Future<Void>>();
    final ExecutorService executorService =
        Executors.newFixedThreadPool(numClients);
    final Configuration conf = new Configuration();
    conf.setInt(CommonConfigurationKeys.IPC_CLIENT_CONNECT_MAX_RETRIES_KEY, 0);
    conf.setBoolean(CommonConfigurationKeys.IPC_NAMESPACE +
        ".0." + CommonConfigurationKeys.IPC_BACKOFF_ENABLE, true);
    final Server server = new RPC.Builder(conf)
        .setProtocol(TestProtocol.class).setInstance(new TestImpl())
        .setBindAddress(ADDRESS).setPort(0)
        .setQueueSizePerHandler(1).setNumHandlers(1).setVerbose(true)
        .build();
    @SuppressWarnings("unchecked")
    CallQueueManager<Call> spy = spy((CallQueueManager<Call>) Whitebox
        .getInternalState(server, "callQueue"));
    Whitebox.setInternalState(server, "callQueue", spy);
    server.start();

    Exception lastException = null;
    final TestProtocol proxy =
        RPC.getProxy(TestProtocol.class, TestProtocol.versionID,
            NetUtils.getConnectAddress(server), conf);
    try {
      // start a sleep RPC call to consume the only handler thread.
      // Start another sleep RPC call to make callQueue full.
      // Start another sleep RPC call to make reader thread block on CallQueue.
      for (int i = 0; i < numClients; i++) {
        res.add(executorService.submit(
            new Callable<Void>() {
              @Override
              public Void call() throws IOException, InterruptedException {
                proxy.sleep(100000);
                return null;
              }
            }));
        verify(spy, timeout(500).times(i + 1)).offer(Mockito.<Call>anyObject());
      }
      try {
        proxy.sleep(100);
      } catch (RemoteException e) {
        IOException unwrapExeption = e.unwrapRemoteException();
        if (unwrapExeption instanceof RetriableException) {
            succeeded = true;
        } else {
          lastException = unwrapExeption;
        }
      }
    } finally {
      server.stop();
      RPC.stopProxy(proxy);
      executorService.shutdown();
    }
    if (lastException != null) {
      LOG.error("Last received non-RetriableException:", lastException);
    }
    assertTrue("RetriableException not received", succeeded);
  }

  /**
   *  Test RPC backoff by response time of each priority level.
   */
  @Test (timeout=30000)
  public void testClientBackOffByResponseTime() throws Exception {
    Server server;
    final TestProtocol proxy;
    boolean succeeded = false;
    final int numClients = 1;

    GenericTestUtils.setLogLevel(DecayRpcScheduler.LOG, Level.DEBUG);
    GenericTestUtils.setLogLevel(RPC.LOG, Level.DEBUG);

    final List<Future<Void>> res = new ArrayList<Future<Void>>();
    final ExecutorService executorService =
        Executors.newFixedThreadPool(numClients);
    conf.setInt(CommonConfigurationKeys.IPC_CLIENT_CONNECT_MAX_RETRIES_KEY, 0);
    final String ns = CommonConfigurationKeys.IPC_NAMESPACE + ".0";
    server = setupDecayRpcSchedulerandTestServer(ns + ".");
    server.start();

    proxy = RPC.getProxy(TestProtocol.class, TestProtocol.versionID,
        NetUtils.getConnectAddress(server), conf);

    @SuppressWarnings("unchecked")
    CallQueueManager<Server.Call> spy = spy((CallQueueManager<Server.Call>)
        Whitebox.getInternalState(server, "callQueue"));
    setInternalState(server, "callQueue", spy);

    Exception lastException = null;
    MetricsRecordBuilder rb1 =
        getMetrics("DecayRpcSchedulerMetrics2." + ns);
    final long beginDecayedCallVolume = MetricsAsserts.getLongCounter(
        "DecayedCallVolume", rb1);
    final long beginRawCallVolume = MetricsAsserts.getLongCounter(
        "CallVolume", rb1);
    final int beginUniqueCaller = MetricsAsserts.getIntCounter("UniqueCallers",
        rb1);

    try {
      // start a sleep RPC call that sleeps 3s.
      for (int i = 0; i < numClients; i++) {
        res.add(executorService.submit(
            new Callable<Void>() {
              @Override
              public Void call() throws IOException, InterruptedException {
                proxy.sleep(3000);
                return null;
              }
            }));
        verify(spy, timeout(500).times(i + 1)).offer(
            Mockito.<Server.Call>anyObject());
      }
      // Start another sleep RPC call and verify the call is backed off due to
      // avg response time(3s) exceeds threshold (2s).
      try {
        // wait for the 1st response time update
        Thread.sleep(5500);
        proxy.sleep(100);
      } catch (RemoteException e) {
        IOException unwrapExeption = e.unwrapRemoteException();
        if (unwrapExeption instanceof RetriableException) {
          succeeded = true;
        } else {
          lastException = unwrapExeption;
        }

        // Lets Metric system update latest metrics
        GenericTestUtils.waitFor(new Supplier<Boolean>() {
          @Override
          public Boolean get() {
            MetricsRecordBuilder rb2 =
              getMetrics("DecayRpcSchedulerMetrics2." + ns);
            long decayedCallVolume1 = MetricsAsserts.getLongCounter(
                "DecayedCallVolume", rb2);
            long rawCallVolume1 = MetricsAsserts.getLongCounter(
                "CallVolume", rb2);
            int uniqueCaller1 = MetricsAsserts.getIntCounter(
                "UniqueCallers", rb2);
            long callVolumePriority0 = MetricsAsserts.getLongGauge(
                "Priority.0.CompletedCallVolume", rb2);
            long callVolumePriority1 = MetricsAsserts.getLongGauge(
                "Priority.1.CompletedCallVolume", rb2);
            double avgRespTimePriority0 = MetricsAsserts.getDoubleGauge(
                "Priority.0.AvgResponseTime", rb2);
            double avgRespTimePriority1 = MetricsAsserts.getDoubleGauge(
                "Priority.1.AvgResponseTime", rb2);

            LOG.info("DecayedCallVolume: " + decayedCallVolume1);
            LOG.info("CallVolume: " + rawCallVolume1);
            LOG.info("UniqueCaller: " + uniqueCaller1);
            LOG.info("Priority.0.CompletedCallVolume: " + callVolumePriority0);
            LOG.info("Priority.1.CompletedCallVolume: " + callVolumePriority1);
            LOG.info("Priority.0.AvgResponseTime: " + avgRespTimePriority0);
            LOG.info("Priority.1.AvgResponseTime: " + avgRespTimePriority1);

            return decayedCallVolume1 > beginDecayedCallVolume &&
                rawCallVolume1 > beginRawCallVolume &&
                uniqueCaller1 > beginUniqueCaller;
          }
        }, 30, 60000);
      }
    } finally {
      server.stop();
      RPC.stopProxy(proxy);
      executorService.shutdown();
    }
    if (lastException != null) {
      LOG.error("Last received non-RetriableException:", lastException);
    }
    assertTrue("RetriableException not received", succeeded);
  }

  private Server setupDecayRpcSchedulerandTestServer(String ns)
      throws Exception {
    final int queueSizePerHandler = 3;

    conf.setInt(CommonConfigurationKeys.IPC_CLIENT_CONNECT_MAX_RETRIES_KEY, 0);
    conf.setBoolean(ns + CommonConfigurationKeys.IPC_BACKOFF_ENABLE, true);
    conf.setStrings(ns + CommonConfigurationKeys.IPC_CALLQUEUE_IMPL_KEY,
        "org.apache.hadoop.ipc.FairCallQueue");
    conf.setStrings(ns + CommonConfigurationKeys.IPC_SCHEDULER_IMPL_KEY,
        "org.apache.hadoop.ipc.DecayRpcScheduler");
    conf.setInt(ns + CommonConfigurationKeys.IPC_SCHEDULER_PRIORITY_LEVELS_KEY,
        2);
    conf.setBoolean(ns +
            DecayRpcScheduler.IPC_DECAYSCHEDULER_BACKOFF_RESPONSETIME_ENABLE_KEY,
        true);
    // set a small thresholds 2s and 4s for level 0 and level 1 for testing
    conf.set(ns +
            DecayRpcScheduler.IPC_DECAYSCHEDULER_BACKOFF_RESPONSETIME_THRESHOLDS_KEY
        , "2s, 4s");

    // Set max queue size to 3 so that 2 calls from the test won't trigger
    // back off because the queue is full.
    // Set max queue size to 3 so that 2 calls from the test won't trigger
    // back off because the queue is full.
    Server server = new RPC.Builder(conf)
        .setProtocol(TestProtocol.class).setInstance(new TestImpl())
        .setBindAddress(ADDRESS).setPort(0)
        .setQueueSizePerHandler(queueSizePerHandler).setNumHandlers(1)
        .setVerbose(true).build();
    return server;
  }

  protected static TestProtocol getClient(InetSocketAddress serverAddr,
      Configuration clientConf) throws ServiceException {
    try {
      return RPC.getProxy(TestProtocol.class, TestProtocol.versionID,
          serverAddr, clientConf);
    } catch (IOException e) {
      throw new ServiceException(e);
    }
  }

  /**
   *  Test RPC timeout.
   */
  @Test(timeout=30000)
  public void testClientRpcTimeout() throws Exception {
    final Server server = new RPC.Builder(conf)
        .setProtocol(TestProtocol.class).setInstance(new TestImpl())
        .setBindAddress(ADDRESS).setPort(0)
        .setQueueSizePerHandler(1).setNumHandlers(1).setVerbose(true)
        .build();
    server.start();

    final InetSocketAddress addr = NetUtils.getConnectAddress(server);
    TestProtocol proxy = null;

    try {
      // Test RPC timeout with default ipc.client.ping.
      try {
        Configuration c = new Configuration(conf);
        c.setInt(CommonConfigurationKeys.IPC_CLIENT_RPC_TIMEOUT_KEY, 1000);
        proxy = getClient(addr, c);
        proxy.sleep(3000);
        fail("RPC should time out.");
      } catch (SocketTimeoutException e) {
        assertTrue(e.getCause() instanceof SocketTimeoutException);
        LOG.info("got expected timeout.", e);
      }

      // Test RPC timeout when ipc.client.ping is false.
      try {
        Configuration c = new Configuration(conf);
        c.setBoolean(CommonConfigurationKeys.IPC_CLIENT_PING_KEY, false);
        c.setInt(CommonConfigurationKeys.IPC_CLIENT_RPC_TIMEOUT_KEY, 1000);
        proxy = getClient(addr, c);
        proxy.sleep(3000);
        fail("RPC should time out.");
      } catch (SocketTimeoutException e) {
        assertTrue(e.getCause() instanceof SocketTimeoutException);
        LOG.info("got expected timeout.", e);
      }

      // Test negative timeout value.
      try {
        Configuration c = new Configuration(conf);
        c.setInt(CommonConfigurationKeys.IPC_CLIENT_RPC_TIMEOUT_KEY, -1);
        proxy = getClient(addr, c);
        proxy.sleep(2000);
      } catch (SocketTimeoutException e) {
        LOG.info("got unexpected exception.", e);
        fail("RPC should not time out.");
      }

      // Test RPC timeout greater than ipc.ping.interval.
      try {
        Configuration c = new Configuration(conf);
        c.setBoolean(CommonConfigurationKeys.IPC_CLIENT_PING_KEY, true);
        c.setInt(CommonConfigurationKeys.IPC_PING_INTERVAL_KEY, 800);
        c.setInt(CommonConfigurationKeys.IPC_CLIENT_RPC_TIMEOUT_KEY, 1000);
        proxy = getClient(addr, c);

        // should not time out because effective rpc-timeout is
        // multiple of ping interval: 1600 (= 800 * (1000 / 800 + 1))
        proxy.sleep(1300);

        proxy.sleep(2000);
        fail("RPC should time out.");
      } catch (SocketTimeoutException e) {
        assertTrue(e.getCause() instanceof SocketTimeoutException);
        LOG.info("got expected timeout.", e);
      }
    } finally {
      server.stop();
      RPC.stopProxy(proxy);
    }
  }

  public static void main(String[] args) throws IOException {
    new TestRPC().testCallsInternal(conf);
  }
}
