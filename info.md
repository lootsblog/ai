Container Configuration:
├── Total Container Memory: 4GB (4096MB)
├── Container Memory Usage: 32% = ~1.3GB
└── But JVM Heap is MUCH smaller!

JVM Heap Reality:
├── JVM sees: 4GB total system memory
├── JVM default heap: ~25% of system memory = 1GB
├── But container overhead reduces available heap
├── Actual JVM heap: ~512MB - 768MB
└── Your app needs: ~800MB+ for 163k assets

Why You See 32% Container Memory Usage:
Container Memory Usage (4GB total):
├── JVM Heap: 512MB - 768MB
├── JVM Non-Heap: ~300MB (Metaspace, Code Cache, etc.)
├── OS Overhead: ~200MB
├── Other processes: ~100MB
└── Total Used: ~1.3GB = 32% of 4GB container

But JVM heap is full at 512MB - 768MB!


Default JVM Behavior in Containers:
# JVM version < 8u191 (older versions):
# - Sees total system memory (4GB)
# - Ignores container limits
# - Sets heap based on system memory
# - But container enforces limits

# JVM version >= 8u191 (newer versions):
# - Container-aware
# - Should respect container limits
# - But still conservative with heap sizing
Look at your task definition:
{
  "memory": 4096,           // Container gets 4GB
  "memoryReservation": 2048,
  "environment": [
    {
      "name": "JAVA_OPTS",
      "value": "-Xmx????"     // What's your heap setting?
    }
  ]
}



The Solution: Explicit JVM Heap Configuration:
Option 1: Set Explicit Heap Size (Recommended)
# In your ECS task definition environment:
JAVA_OPTS="-Xmx3072m -Xms1024m -XX:+UseG1GC -XX:+PrintGCDetails"

# Breakdown:
# -Xmx3072m : Max heap = 3GB (75% of 4GB container)
# -Xms1024m : Initial heap = 1GB
# -XX:+UseG1GC : Better garbage collector for large heaps
# -XX:+PrintGCDetails : See GC activity in logs


Option 2: Container-Aware JVM (If using Java 11+)
JAVA_OPTS="-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -XX:MaxRAMFraction=2"

# This tells JVM:
# - Use container memory limits
# - Set heap to 50% of container memory (2GB out of 4GB)


Option 3: Modern Java (Java 17+)
JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=25.0"

# Modern way:
# - MaxRAMPercentage=75.0 : Use 75% of container memory for heap
# - InitialRAMPercentage=25.0 : Start with 25% of container memory

CPU Spike Explanation:
1. Application starts processing 163k assets
2. Heap fills up quickly (512MB is too small)
3. Garbage collector runs continuously (CPU spike!)
4. GC can't free enough memory (heap too small)
5. Eventually: OutOfMemoryError: Java heap space
6. Container memory shows 32% (misleading!)

   The 32% is real container memory usage, but:
- JVM heap is maxed out at ~512MB
- Container still has 2.5GB+ available
- But JVM can't access it without proper configuration


Expected Results After Fix:
Before:
├── Container Memory: 32% (1.3GB)
├── JVM Heap: 512MB (FULL)
├── CPU: 100% (GC thrashing)
└── Result: OutOfMemoryError

After:
├── Container Memory: 60-70% (2.5-3GB)
├── JVM Heap: 3GB (plenty of room)
├── CPU: 30-50% (normal processing)
└── Result: Successful export


 Runtime runtime = Runtime.getRuntime();
        long maxHeap = runtime.maxMemory();
        long totalHeap = runtime.totalMemory();
        long freeHeap = runtime.freeMemory();
        
        log.info("=== JVM MEMORY INFO AT STARTUP ===");
        log.info("Max Heap: {}MB", maxHeap / 1024 / 1024);
        log.info("Total Heap: {}MB", totalHeap / 1024 / 1024);
        log.info("Free Heap: {}MB", freeHeap / 1024 / 1024);
        log.info("Used Heap: {}MB", (totalHeap - freeHeap) / 1024 / 1024);
        log.info("Available for allocation: {}MB", (maxHeap - totalHeap + freeHeap) / 1024 / 1024);
        log.info("=========================================");
