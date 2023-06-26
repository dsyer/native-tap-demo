
Total time for client to receive response is measured like this:

```
$ curl -o /dev/null -H 'Cache-Control: no-cache' -s -w "Connect: %{time_connect} TTFB: %{time_starttransfer} Total time: %{time_total} \n" app.my-apps.tap.dsyer-tap-demo.tapdemo.vmware.com
Connect: 0.131538 TTFB: 1.341332 Total time: 1.341452
```

The time to first byte (TTFB) is about 1300ms. Fluctuates by up to 200ms if you try it a few times. With a warm service TTFB is about 180ms, so that's the "normal" latency, mostly consisting of network 
travel time to abd from Azure servers (estimated 160ms based on ping times).

Here's what we can learn from the logs with `logging.level.org.springframework.web=trace`:

| Time(ms)  | Activity |
| ------------- | ------------- |
| 180  | Cluster response time for warm service |
| 80   | Application startup |
| 660  | Waiting for first request to `/readyz` |
| 30   | Delay between `/readyz` and queued request to `/` |
| 390  | General pod scheduling and container startup |

* 390ms for scheduling and container startup seems pretty fast. I'm sure there are ways to attack it, but it's not a bad start.
* 660ms waiting for the first request to `/readyz` is a bit long. I'm not sure why it's so long, because as far as we know the autoscaler polls every 200ms.

## TAP Notes

TAP version:

```
$ kubectl get pkgi -n tap-install | grep tap.tanzu
tap                                  tap.tanzu.vmware.com                                  1.6.0-build.6          Reconcile succeeded   27h
```

Install Zipkin:

```
$ kubectl apply -f config/zipkin.yaml
```

Pause the package install for TAP in the `kapp-controller` (or you could just `kubectl edit` those changes instead):

```
kubectl patch -n tap-install pkgi tap --type=merge -p '{"spec":{"paused":true}}'
kubectl patch -n tap-install pkgi cnrs --type=merge -p '{"spec":{"paused":true}}'
```

Update the tracing configuration in `knative-serving`:

```
$ kubectl apply -f config/tracing.yaml
```

Go to the Zipkin UI and browse for traces. Here's an example of a trace for a request to the application when it needs a cold start:

![Zipkin Trace](images/zipkin.png)

That's actually the only trace when activating my app (scaling up from 0 to 1) and scaling back down to 0. It doesn't tell us much, except that the activator-service agrees with us that it takes 1.3s. There's no detail in the "throttler_try" span (which is the long one). It's just a single event with no details.

### AKS

Kubelet logs:

```
$ for f in `kubectl get nodes -o custom-columns=:.metadata.name`; do kubectl get --raw "/api/v1/nodes/$f/proxy/logs/messages"; done
```

| Kubelet Event | Timeline(ms) | Delta(ms)  | Node Event | Application Event |
| -- |------- | ------ | ------------- | ------------- |
| 0  |   0 |     | SyncLoop ADD | 
| 1  |   0 |   0 | RemoveStaleState removing state | 
| 2  |  65 |  65 | operationExecutor.VerifyControllerAttachedVolume started for volume |
| 3  | 165 | 100 | operationExecutor.MountVolume started for volume |
| 4  | 190 |  25 | MountVolume.SetUp succeeded for volume |
| 5  | 320 | 130 | No sandbox for pod can be found. Need to start a new one |
|5.0 | 321 |   1 | containerd: RunPodSandbox |
|5.1 |     |     | systemd-networkd: Link UP |
|5.2 | 396 |  75 | kernel: cbr0: port entered blocking state
|    |     |     | systemd-networkd: Gained carrier |
|5.3 |     |     | kernel: eth0: link becomes ready
|5.4 | 396 |   0 | containerd: loading plugin |
|5.5 | 476 |  80 | containerd: RunPodSandbox |
|5.6 | 476 |   0 | containerd: CreateContainer (workload) |
|5.7 | 506 |  30 | containerd: StartContainer |
|    | 565 |     |                                 | Process starts (extrapolated back)|
|5.8 | 556 |  50 | containerd: StartContainer |
|5.9 | 556 |   0 | containerd: CreateContainer (queue-proxy) |
|5.10| 596 |  40 | containerd: StartContainer |
|    | 640 |     |                                 | Starting AOT-processed DemoApplication |
|    | 725 |     |                                 | Started DemoApplication in 0.153 seconds (process running for 0.157) |
| 6  | 730 | 135 | Type:ContainerStarted |
| 7  | 730 |   0 | Type:ContainerStarted |
|7.1 | 740 |  10 | containerd: StartContainer |
| 8  |1730 | 990 | Type:ContainerStarted |
|    |1740 |     |                                 | First request to /readyz |
| 9  |1740 |  10 | probe="readiness" status="" |
|10  |1744 |   4 | probe="readiness" status="ready" |

The application starts in between #5 and #6, and it reports that it has started after 157ms (before event #6). The total time to first byte from the client in this case was longer (2100ms), which is roughly consistent with the Kubelet timeline.

I tried it a couple of times and always see those 3 `ContainerStarted` events, but sometimes they come at the same time instead of being staggered a bit like this example. In that case there is still a delay of 900-1000ms between event #5 and #8.

### GKE

| Kubelet Event | Timeline(ms) | Delta(ms)  | Node Event | Application Event |
| -- |------- | ------ | ------------- | ------------- |
| 0  |   0 |     | SyncLoop ADD | 
| 1  |   0 |   0 | RemoveStaleState removing state | 
| 2  |  60 |  60 | operationExecutor.VerifyControllerAttachedVolume started for volume |
| 3  | 160 | 100 | operationExecutor.MountVolume started for volume |
| 4  | 170 |  10 | MountVolume.SetUp succeeded for volume |
| 5  | 320 | 150 | No sandbox for pod can be found. Need to start a new one |
|5.0 | 321 |   1 | containerd: RunPodSandbox |
|5.1 | 395 |  74 | containerd: loading plugin |
|5.5 | 490 |  95 | containerd: RunPodSandbox |
|5.6 | 500 |  10 | containerd: PullImage |
| 6  | 610 | 110 | Type:ContainerStarted |
|6.1 | 760 | 150 | containerd: trying next host |
|6.2 | 790 |  30 | Syncing iptables rules |
|6.3 | 810 |  20 | SyncProxyRules complete |
|6.4 |4335 |3525 | containerd: ImageCreate |
|6.5 |4360 |  25 | containerd: StartContainer |
|6.6 |4430 |  70 | containerd: StartContainer |
|6.7 |4455 |  25 | containerd: StartContainer |
|6.8 |4540 |  85 | containerd: StartContainer |
| 7  |4630 |  90 | Type:ContainerStarted |
| 8  |4630 |   0 | Type:ContainerStarted |
| 9  |4630 |   0 | probe="readiness" status="" |
|10  |4640 |  10 | probe="readiness" status="ready" |

There's a long delay while containerd pulls an image. Everything else is pretty similar. The GKE cluster is much bigger and the scheduler is more likely to land the app on a node where the image isn't cached. The AKS cluster is only 4 nodes. The GKE cluster is 15 (shared with a team).

To test that we can run our curl command a few times (every 5 minutes to let the activator-service scale back down to 0):

```
Connect      TTFB
0.158462     13.115335
0.158659      1.758999
0.175817     11.489829
0.173374     11.471310
0.159171      1.395872
0.186598      1.629641
0.218800      1.611680
0.160281      1.734112
```

So the image cache is more important in GKE and/or a larger cluster but you can see similar "warm cold" start times across platforms once the cache is taken into account. Looking at the logs from GKE the timeline for the startup is very similar to the AKS one above. There is a 400ms delay between the app starting up and the first request to `/readyz` (which is better than the AKS cluster but still annoying).