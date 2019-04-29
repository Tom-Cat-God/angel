package com.tencent.angel.kubernetesmanager.scheduler

import com.tencent.angel.kubernetesmanager.deploy.utils.{AngelKubernetesClientFactory, ThreadUtils}
import com.tencent.angel.conf.AngelConf
import org.apache.hadoop.conf.Configuration

private[angel] class KubernetesClusterManager {

  private var psSchedulerBackend: KubernetesClusterSchedulerBackend = _
  private var workerSchedulerBackend: KubernetesClusterSchedulerBackend = _

  def doScheduler(conf: Configuration): Unit = {
    val (authConfPrefix,
      apiServerUri,
      defaultServiceAccountToken,
      defaultServiceAccountCaCrt) = (conf.get(AngelConf.ANGEL_KUBERNETES_KUBERNETES_AUTH_CLIENT_MODE_PREFIX),
      conf.get(AngelConf.ANGEL_KUBERNETES_MASTER),
      None,
      None)
    Option(conf.get(AngelConf.ANGEL_KUBERNETES_MASTER_POD_NAME))
      .getOrElse("The angel master pod name must be provided.")
    val namespace = conf.get(AngelConf.ANGEL_KUBERNETES_NAMESPACE, AngelConf.DEFAULT_ANGEL_KUBERNETES_NAMESPACE)
    val kubernetesClient = AngelKubernetesClientFactory.createKubernetesClient(
      apiServerUri,
      Some(namespace),
      authConfPrefix,
      conf,
      defaultServiceAccountToken,
      defaultServiceAccountCaCrt)
    val requestExecutorsService =  ThreadUtils.newDaemonCachedThreadPool(
      "kubernetes-executor-requests")
    val subscribersExecutor = ThreadUtils
      .newDaemonThreadPoolScheduledExecutor(
        "kubernetes-executor-snapshots-subscribers", 2)
    val snapshotsStore = new AngelExecutorPodsSnapshotsStoreImpl(subscribersExecutor)
    val executorPodsLifecycleEventHandler = new AngelExecutorPodsLifecycleManager(
      conf,
      new KubernetesAngelExecutorBuilder(),
      kubernetesClient,
      snapshotsStore)
    val executorPodsAllocator = new AngelExecutorPodsAllocator(
      conf, new KubernetesAngelExecutorBuilder(), kubernetesClient, snapshotsStore)

    val podsWatchEventSource = new AngelExecutorPodsWatchSnapshotSource(
      conf,
      snapshotsStore,
      kubernetesClient)

    val eventsPollingExecutor = ThreadUtils.newDaemonSingleThreadScheduledExecutor(
      "kubernetes-executor-pod-polling-sync")
    val podsPollingEventSource = new AngelExecutorPodsPollingSnapshotSource(
      conf, kubernetesClient, snapshotsStore, eventsPollingExecutor)

    if (psRole(conf)) {
      psSchedulerBackend = new KubernetesClusterSchedulerBackend(
        conf,
        kubernetesClient,
        requestExecutorsService,
        snapshotsStore,
        executorPodsAllocator,
        executorPodsLifecycleEventHandler,
        podsWatchEventSource,
        podsPollingEventSource)
    } else {
      workerSchedulerBackend = new KubernetesClusterSchedulerBackend(
        conf,
        kubernetesClient,
        requestExecutorsService,
        snapshotsStore,
        executorPodsAllocator,
        executorPodsLifecycleEventHandler,
        podsWatchEventSource,
        podsPollingEventSource)
    }
  }

  def scheduler(conf: Configuration): Unit = {
    doScheduler(conf)
    if (psRole(conf)) {
      psSchedulerBackend.start()
    } else {
      workerSchedulerBackend.start()
    }
  }

  def psRole(conf: Configuration): Boolean = {
    val executorRole = conf.get(AngelConf.ANGEL_KUBERNETES_EXECUTOR_ROLE,
      AngelConf.DEFAULT_ANGEL_KUBERNETES_EXECUTOR_ROLE)
    executorRole.equals("ps")
  }

  def stop(conf: Configuration): Unit = {
    if (psRole(conf)) {
      psSchedulerBackend.stop()
    } else {
      workerSchedulerBackend.stop()
    }
  }
}
