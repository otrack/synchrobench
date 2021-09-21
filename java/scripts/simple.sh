#!/bin/sh

for i in 1 2 4 8 16 24; do numactl --cpubind 0 --membind 0 java -cp lib/dobj-1.0.jar:bin contention.benchmark.Test -W 5 -u 100 -a 0 -s 0 -l 10000 -t ${i}  -i 16384 -r 32768 -b queues.lockfree.LockFreeDQueueIntSet  | grep Throughput | awk -v threads=$i '{print threads" "$3}'; done
