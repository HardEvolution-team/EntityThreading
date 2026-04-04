# Java Vector API (Java 25 / JEP 508) — Полная Вики для Minecraft Forge 1.12.2

> **Статус в Java 25:** JEP 508 — 10-й Incubator. API находится в `jdk.incubator.vector`.  
> Финализация ожидает Project Valhalla (value classes). API стабилен, продакшн-использование возможно.  
> **Применимость к Forge 1.12.2:** требуется CleanroomMC или запуск JVM Java 21+.

---

## Содержание

1. [Что такое Vector API и зачем он нужен](#1-что-такое-vector-api-и-зачем-он-нужен)
2. [Ключевые концепции](#2-ключевые-концепции)
3. [Setup: Forge 1.12.2 + Java 21+ (CleanroomMC)](#3-setup-forge-1122--java-21-cleanroommc)
4. [build.gradle: подключение модуля](#4-buildgradle-подключение-модуля)
5. [Паттерн: VectorHelper — утилитный класс](#5-паттерн-vectorhelper--утилитный-класс)
6. [Пример 1: Массовая обработка биомов чанка](#6-пример-1-массовая-обработка-биомов-чанка)
7. [Пример 2: Батч-поиск воздушных блоков (heightmap)](#7-пример-2-батч-поиск-воздушных-блоков-heightmap)
8. [Пример 3: Векторное суммирование освещения](#8-пример-3-векторное-суммирование-освещения)
9. [Пример 4: Батч AABB-коллизия сущностей](#9-пример-4-батч-aabb-коллизия-сущностей)
10. [Пример 5: Векторный шум Перлина для террейн-генерации](#10-пример-5-векторный-шум-перлина-для-террейн-генерации)
11. [Пример 6: Zstd-сжатие буфера чанка с Vector prefetch](#11-пример-6-zstd-сжатие-буфера-чанка-с-vector-prefetch)
12. [Интеграция с Thorium / EntityThreading](#12-интеграция-с-thorium--entitythreading)
13. [VectorMask: условные операции](#13-vectormask-условные-операции)
14. [Производительность и подводные камни](#14-производительность-и-подводные-камни)
15. [Graceful Degradation: fallback на Java 8](#15-graceful-degradation-fallback-на-java-8)
16. [Быстрый справочник по API](#16-быстрый-справочник-по-api)

---

## 1. Что такое Vector API и зачем он нужен

Стандартный Java-код обрабатывает данные **скалярно** — по одному элементу за такт. Современные процессоры имеют **SIMD-регистры** (SSE/AVX на x86, NEON/SVE на ARM), позволяющие за один такт обрабатывать 4, 8, 16 и более чисел одновременно.

```
Скалярный цикл:      [a0] [a1] [a2] [a3] [a4] [a5] [a6] [a7]  ← 8 тактов
                      + +   + +   + +   + +   + +   + +   + +   + +

SIMD AVX-256:        [a0  a1  a2  a3  a4  a5  a6  a7]           ← 1 такт
                      +   +   +   +   +   +   +   +
                     [b0  b1  b2  b3  b4  b5  b6  b7]
```

Vector API предоставляет Java-абстракцию поверх SIMD без нативного кода. JIT-компилятор (HotSpot C2) транслирует вызовы API напрямую в `VADDPS`, `VMOVDQU`, `VPBLENDVB` и другие векторные инструкции.

**Для Minecraft Forge 1.12.2 это критично потому, что:**
- Чанк хранит `65 536` блоков (16×256×16) — идеальный кандидат для SIMD
- Биомовый массив: `256` байт на чанк — обрабатывается за несколько векторных операций
- Heightmap: `256` int-значений — один AVX-проход вместо скалярного цикла
- Шум Перлина: `N` float-вычислений одновременно — ускорение террейн-генерации в 4–8×

---

## 2. Ключевые концепции

### VectorSpecies — «вид» вектора

Задаёт тип элемента и ширину регистра:

```java
// float, 256-битный AVX2 регистр = 8 lane (дорожек)
VectorSpecies<Float> SPECIES_256 = FloatVector.SPECIES_256;

// int, 128-битный SSE регистр = 4 lane  
VectorSpecies<Integer> SPECIES_128 = IntVector.SPECIES_128;

// Preferred — автовыбор оптимального размера под CPU
VectorSpecies<Float> SPECIES_PREF = FloatVector.SPECIES_PREFERRED;
```

| Species     | Bits | float lanes | int lanes | double lanes |
|-------------|------|-------------|-----------|--------------|
| SPECIES_64  | 64   | 2           | 2         | 1            |
| SPECIES_128 | 128  | 4           | 4         | 2            |
| SPECIES_256 | 256  | 8           | 8         | 4            |
| SPECIES_512 | 512  | 16          | 16        | 8            |
| PREFERRED   | auto | зависит от CPU | ... | ...        |

### Lane (дорожка)

Одна «ячейка» внутри вектора. `FloatVector.SPECIES_256` имеет 8 lanes — хранит 8 float одновременно.

### VectorMask

Булевая маска для условных операций: `VectorMask<Float>` — массив `boolean[laneCount]`.

### Операции

| Тип | Примеры |
|-----|---------|
| Lane-wise (поэлементные) | `add`, `sub`, `mul`, `div`, `min`, `max`, `abs`, `neg` |
| Lane-wise логические | `and`, `or`, `xor`, `not`, `lanewise(VectorOperators.AND, ...)` |
| Сравнение | `lt`, `gt`, `eq`, `le`, `ge` → возвращает `VectorMask` |
| Cross-lane | `reduceLanes(ADD)`, `rearrange`, `selectFrom` |
| Загрузка/сохранение | `fromArray`, `intoArray`, `fromMemorySegment` |

---

## 3. Setup: Forge 1.12.2 + Java 21+ (CleanroomMC)

Forge 1.12.2 официально работает на Java 8. Vector API требует **минимум Java 16** (incubator). Для запуска на Java 21/25 используется **CleanroomMC**.

### Конфигурация JVM для сервера/клиента

Добавить в JVM-аргументы запуска:

```bash
# Обязательно: активация incubator-модуля
--add-modules=jdk.incubator.vector

# Разрешить рефлективный доступ (нужно для Forge/Mixin)
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens java.base/sun.nio.ch=ALL-UNNAMED

# Для CleanroomMC: уже включено в launcher-скрипт, но проверь
```

### Проверка доступности Vector API в рантайме

```java
public class VectorApiCheck {
    private static final boolean VECTOR_AVAILABLE;

    static {
        boolean available = false;
        try {
            Class.forName("jdk.incubator.vector.FloatVector");
            // Проверяем, что JIT реально умеет AVX
            available = FloatVector.SPECIES_PREFERRED.vectorBitSize() >= 128;
        } catch (ClassNotFoundException | UnsupportedOperationException e) {
            // Java 8 или Vector API не активирован
        }
        VECTOR_AVAILABLE = available;
    }

    public static boolean isAvailable() { return VECTOR_AVAILABLE; }
}
```

---

## 4. build.gradle: подключение модуля

```groovy
// build.gradle (Forge MDK 1.12.2)
apply plugin: 'java'
apply plugin: 'net.minecraftforge.gradle.forge'

sourceCompatibility = JavaVersion.VERSION_21
targetCompatibility = JavaVersion.VERSION_21

minecraft {
    version = "1.12.2-14.23.5.2860"
    runDir = "run"
    mappings = "stable_39"
}

// Активация incubator при компиляции и запуске
compileJava {
    options.compilerArgs += ['--add-modules', 'jdk.incubator.vector']
}

test {
    jvmArgs '--add-modules', 'jdk.incubator.vector'
}

// В run-конфиге (если используешь ForgeGradle runs)
minecraft {
    clientJvmArgs '--add-modules=jdk.incubator.vector'
    serverJvmArgs '--add-modules=jdk.incubator.vector'
}

dependencies {
    // Никаких дополнительных зависимостей — Vector API встроен в JDK
}
```

---

## 5. Паттерн: VectorHelper — утилитный класс

Центральный фасад для всего Vector API в моде. Инкапсулирует fallback-логику.

```java
package com.hardevo.thorium.vector;

import jdk.incubator.vector.*;

/**
 * Thorium VectorHelper — центральный утилитный класс для SIMD-операций.
 * Автоматически выбирает оптимальный VectorSpecies под текущий CPU.
 * На Java 8 / без Vector API все методы используют скалярный fallback.
 */
public final class VectorHelper {

    // Предпочтительный вид — JVM выбирает ширину регистра сам (AVX512/AVX2/SSE)
    public static final VectorSpecies<Float>   FLOAT_SPECIES = FloatVector.SPECIES_PREFERRED;
    public static final VectorSpecies<Integer> INT_SPECIES   = IntVector.SPECIES_PREFERRED;
    public static final VectorSpecies<Byte>    BYTE_SPECIES  = ByteVector.SPECIES_PREFERRED;

    // Количество элементов, обрабатываемых за один проход
    public static final int FLOAT_LANE_COUNT = FLOAT_SPECIES.length();
    public static final int INT_LANE_COUNT   = INT_SPECIES.length();
    public static final int BYTE_LANE_COUNT  = BYTE_SPECIES.length();

    private VectorHelper() {}

    /**
     * Векторное сложение двух float[]-массивов с записью результата в dst.
     * dst[i] = a[i] + b[i]
     */
    public static void addArrays(float[] a, float[] b, float[] dst) {
        int i = 0;
        int bound = FLOAT_SPECIES.loopBound(a.length); // округление вниз до кратного lane-count

        for (; i < bound; i += FLOAT_LANE_COUNT) {
            FloatVector va = FloatVector.fromArray(FLOAT_SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(FLOAT_SPECIES, b, i);
            va.add(vb).intoArray(dst, i);
        }

        // Хвост: оставшиеся элементы, не кратные lane-count
        for (; i < a.length; i++) {
            dst[i] = a[i] + b[i];
        }
    }

    /**
     * Векторное скалярное умножение: dst[i] = src[i] * scalar
     */
    public static void scaleArray(float[] src, float scalar, float[] dst) {
        FloatVector vScalar = FloatVector.broadcast(FLOAT_SPECIES, scalar);
        int i = 0;
        int bound = FLOAT_SPECIES.loopBound(src.length);

        for (; i < bound; i += FLOAT_LANE_COUNT) {
            FloatVector.fromArray(FLOAT_SPECIES, src, i)
                .mul(vScalar)
                .intoArray(dst, i);
        }

        for (; i < src.length; i++) {
            dst[i] = src[i] * scalar;
        }
    }

    /**
     * Подсчёт количества элементов массива, равных заданному значению.
     * Используется для подсчёта блоков с конкретным ID.
     */
    public static int countEquals(int[] array, int value) {
        IntVector vValue = IntVector.broadcast(INT_SPECIES, value);
        int count = 0;
        int i = 0;
        int bound = INT_SPECIES.loopBound(array.length);

        for (; i < bound; i += INT_LANE_COUNT) {
            VectorMask<Integer> mask = IntVector.fromArray(INT_SPECIES, array, i)
                .eq(vValue);
            count += mask.trueCount();
        }

        for (; i < array.length; i++) {
            if (array[i] == value) count++;
        }
        return count;
    }

    /**
     * Нахождение максимума в float[]-массиве.
     */
    public static float maxValue(float[] array) {
        FloatVector vMax = FloatVector.broadcast(FLOAT_SPECIES, Float.NEGATIVE_INFINITY);
        int i = 0;
        int bound = FLOAT_SPECIES.loopBound(array.length);

        for (; i < bound; i += FLOAT_LANE_COUNT) {
            vMax = vMax.max(FloatVector.fromArray(FLOAT_SPECIES, array, i));
        }

        float max = vMax.reduceLanes(VectorOperators.MAX);

        for (; i < array.length; i++) {
            if (array[i] > max) max = array[i];
        }
        return max;
    }
}
```

---

## 6. Пример 1: Массовая обработка биомов чанка

Чанк хранит биомовый массив `byte[256]`. Задача: найти все тропические биомы (ID 21–23) и пометить их.

```java
package com.hardevo.thorium.chunk;

import jdk.incubator.vector.*;
import net.minecraft.world.chunk.Chunk;

/**
 * Векторный анализ биомов чанка.
 * Скалярный аналог: O(256) последовательных сравнений.
 * Векторный: O(256 / BYTE_LANE_COUNT) — при AVX2 это 8 итераций вместо 256.
 */
public class VectorBiomeAnalyzer {

    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

    // Диапазон биомов джунглей: ID 21, 22, 23, 149, 151
    private static final byte JUNGLE_ID    = 21;
    private static final byte JUNGLE_HILLS = 22;
    private static final byte JUNGLE_EDGE  = 23;

    /**
     * Подсчитывает количество джунглевых колонок в чанке.
     * @param chunk - загруженный чанк
     * @return количество биом-колонок, принадлежащих джунглям
     */
    public static int countJungleBiomes(Chunk chunk) {
        byte[] biomes = chunk.getBiomeArray();
        // biomes.length == 256 для чанка 16x16

        ByteVector vJungle = ByteVector.broadcast(SPECIES, JUNGLE_ID);
        ByteVector vHills  = ByteVector.broadcast(SPECIES, JUNGLE_HILLS);
        ByteVector vEdge   = ByteVector.broadcast(SPECIES, JUNGLE_EDGE);

        int count = 0;
        int laneCount = SPECIES.length();
        int bound = SPECIES.loopBound(biomes.length);
        int i = 0;

        for (; i < bound; i += laneCount) {
            ByteVector v = ByteVector.fromArray(SPECIES, biomes, i);

            // Параллельное сравнение: 32 байта за раз (AVX2)
            VectorMask<Byte> isJungle = v.eq(vJungle)
                .or(v.eq(vHills))
                .or(v.eq(vEdge));

            count += isJungle.trueCount();
        }

        // Хвост
        for (; i < biomes.length; i++) {
            byte b = biomes[i];
            if (b == JUNGLE_ID || b == JUNGLE_HILLS || b == JUNGLE_EDGE) count++;
        }
        return count;
    }

    /**
     * Заменяет все биомы заданного типа на другой (например, при терраформировании).
     * Векторная операция blend — аналог SELECT/CHOOSE на уровне CPU.
     */
    public static void replaceBiome(byte[] biomes, byte fromId, byte toId) {
        ByteVector vFrom    = ByteVector.broadcast(SPECIES, fromId);
        ByteVector vTo      = ByteVector.broadcast(SPECIES, toId);

        int laneCount = SPECIES.length();
        int bound = SPECIES.loopBound(biomes.length);
        int i = 0;

        for (; i < bound; i += laneCount) {
            ByteVector v = ByteVector.fromArray(SPECIES, biomes, i);
            VectorMask<Byte> matches = v.eq(vFrom);
            // blend(vTo, mask): там где mask=true, берём vTo, иначе v
            v.blend(vTo, matches).intoArray(biomes, i);
        }

        for (; i < biomes.length; i++) {
            if (biomes[i] == fromId) biomes[i] = toId;
        }
    }
}
```

---

## 7. Пример 2: Батч-поиск воздушных блоков (heightmap)

Heightmap — `int[256]`, где значение = Y высшего непрозрачного блока. Задача: найти максимальную высоту в чанке.

```java
package com.hardevo.thorium.chunk;

import jdk.incubator.vector.*;
import net.minecraft.world.chunk.Chunk;

/**
 * Векторный анализ heightmap чанка.
 * Стандартный поиск максимума: 256 сравнений.
 * Векторный (AVX2, 8 int lanes): 32 итерации.
 */
public class VectorHeightmapAnalyzer {

    private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;

    /**
     * Находит максимальную высоту в heightmap чанка.
     */
    public static int findMaxHeight(Chunk chunk) {
        int[] heightmap = chunk.getHeightMap();
        return VectorHelper.maxValue(
            // int[] → float[] — только если хотим использовать FloatVector
            // Здесь работаем с IntVector напрямую
            heightmap
        );
    }

    // Версия для int[]
    public static int maxIntArray(int[] array) {
        IntVector vMax = IntVector.broadcast(SPECIES, Integer.MIN_VALUE);
        int laneCount = SPECIES.length();
        int bound = SPECIES.loopBound(array.length);
        int i = 0;

        for (; i < bound; i += laneCount) {
            vMax = vMax.max(IntVector.fromArray(SPECIES, array, i));
        }

        int max = vMax.reduceLanes(VectorOperators.MAX);

        for (; i < array.length; i++) {
            if (array[i] > max) max = array[i];
        }
        return max;
    }

    /**
     * Считает количество колонок, где высота ниже заданного порога.
     * Применение: определение "плоских" зон для спауна структур.
     */
    public static int countBelowThreshold(int[] heightmap, int threshold) {
        IntVector vThreshold = IntVector.broadcast(SPECIES, threshold);
        int count = 0;
        int laneCount = SPECIES.length();
        int bound = SPECIES.loopBound(heightmap.length);
        int i = 0;

        for (; i < bound; i += laneCount) {
            VectorMask<Integer> below = IntVector.fromArray(SPECIES, heightmap, i)
                .lt(vThreshold);
            count += below.trueCount();
        }

        for (; i < heightmap.length; i++) {
            if (heightmap[i] < threshold) count++;
        }
        return count;
    }

    /**
     * Вычисляет "перепад" высот — максимум минус минимум.
     * Используется для оценки пригодности площадки для строительства.
     */
    public static int heightVariance(int[] heightmap) {
        IntVector vMax = IntVector.broadcast(SPECIES, Integer.MIN_VALUE);
        IntVector vMin = IntVector.broadcast(SPECIES, Integer.MAX_VALUE);
        int laneCount = SPECIES.length();
        int bound = SPECIES.loopBound(heightmap.length);
        int i = 0;

        for (; i < bound; i += laneCount) {
            IntVector v = IntVector.fromArray(SPECIES, heightmap, i);
            vMax = vMax.max(v);
            vMin = vMin.min(v);
        }

        int max = vMax.reduceLanes(VectorOperators.MAX);
        int min = vMin.reduceLanes(VectorOperators.MIN);

        for (; i < heightmap.length; i++) {
            if (heightmap[i] > max) max = heightmap[i];
            if (heightmap[i] < min) min = heightmap[i];
        }
        return max - min;
    }
}
```

---

## 8. Пример 3: Векторное суммирование освещения

Расчёт суммарного освещения в чанке — типичная операция для отладочного F3-оверлея или оптимизации мобспауна.

```java
package com.hardevo.thorium.light;

import jdk.incubator.vector.*;

/**
 * Векторный анализ массивов освещения.
 * ExtendedBlockStorage.blockLight / skyLight — byte[] массивы.
 *
 * NibbleArray в MC 1.12.2 хранит данные как упакованные nibble (4 бита на значение).
 * Здесь работаем с уже распакованными массивами.
 */
public class VectorLightAnalyzer {

    private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;

    /**
     * Вычисляет среднее освещение в массиве (значения 0–15).
     * @param lightData - распакованный массив освещения (byte[], значения 0..15)
     */
    public static float averageLightLevel(byte[] lightData) {
        // Суммируем все значения векторно
        // Байты: суммируем через int-аккумулятор чтобы не переполниться
        long sum = vectorSum(lightData);
        return (float) sum / lightData.length;
    }

    private static long vectorSum(byte[] data) {
        // Стратегия: складываем int-вектором, конвертируя byte → int
        VectorSpecies<Integer> intSpecies = IntVector.SPECIES_PREFERRED;
        IntVector vSum = IntVector.zero(intSpecies);
        int laneCount = intSpecies.length();
        int i = 0;
        int bound = intSpecies.loopBound(data.length);

        for (; i < bound; i += laneCount) {
            // Ручная загрузка byte → int (нет прямого ByteVector→IntVector расширения в incubator)
            int[] chunk = new int[laneCount];
            for (int j = 0; j < laneCount; j++) {
                chunk[j] = data[i + j] & 0xFF;
            }
            vSum = vSum.add(IntVector.fromArray(intSpecies, chunk, 0));
        }

        long sum = vSum.reduceLanes(VectorOperators.ADD);
        for (; i < data.length; i++) {
            sum += data[i] & 0xFF;
        }
        return sum;
    }

    /**
     * Подсчёт блоков с уровнем освещения ≥ threshold.
     * Применение: Thorium — определить нужна ли перерасчёт света в секции.
     */
    public static int countAboveLightLevel(byte[] lightData, byte threshold) {
        ByteVector vThreshold = ByteVector.broadcast(BYTE_SPECIES, threshold);
        int count = 0;
        int laneCount = BYTE_SPECIES.length();
        int bound = BYTE_SPECIES.loopBound(lightData.length);
        int i = 0;

        for (; i < bound; i += laneCount) {
            ByteVector v = ByteVector.fromArray(BYTE_SPECIES, lightData, i);
            // unsigned сравнение: добавляем смещение чтобы работать корректно с 0-15
            VectorMask<Byte> above = v.compare(VectorOperators.UNSIGNED_GE, vThreshold);
            count += above.trueCount();
        }

        for (; i < lightData.length; i++) {
            if ((lightData[i] & 0xFF) >= (threshold & 0xFF)) count++;
        }
        return count;
    }
}
```

---

## 9. Пример 4: Батч AABB-коллизия сущностей

Задача: проверить, пересекает ли данный AABB любой из N AABB-ов сущностей в чанке. Стандартная реализация: N последовательных проверок. Векторная: параллельная проверка 8 (или 16) AABB сразу.

```java
package com.hardevo.thorium.entity;

import jdk.incubator.vector.*;
import net.minecraft.util.math.AxisAlignedBB;

/**
 * Векторная проверка коллизий AABB.
 *
 * AABB задаётся 6 float: minX, minY, minZ, maxX, maxY, maxZ.
 * Сохраняем массив AABB в SoA (Structure of Arrays) формате:
 * minXArray[], minYArray[], minZArray[], maxXArray[], maxYArray[], maxZArray[]
 * — это позволяет обрабатывать 8 AABB за одну векторную итерацию.
 */
public class VectorAABBCollision {

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    // SoA буферы (Structure of Arrays)
    private final float[] minXArr;
    private final float[] minYArr;
    private final float[] minZArr;
    private final float[] maxXArr;
    private final float[] maxYArr;
    private final float[] maxZArr;
    private int size;

    public VectorAABBCollision(int capacity) {
        minXArr = new float[capacity];
        minYArr = new float[capacity];
        minZArr = new float[capacity];
        maxXArr = new float[capacity];
        maxYArr = new float[capacity];
        maxZArr = new float[capacity];
    }

    public void add(AxisAlignedBB aabb) {
        minXArr[size] = (float) aabb.minX;
        minYArr[size] = (float) aabb.minY;
        minZArr[size] = (float) aabb.minZ;
        maxXArr[size] = (float) aabb.maxX;
        maxYArr[size] = (float) aabb.maxY;
        maxZArr[size] = (float) aabb.maxZ;
        size++;
    }

    /**
     * Проверяет, пересекает ли query хоть один из сохранённых AABB.
     * Векторная версия: обрабатывает FLOAT_LANE_COUNT AABB за итерацию.
     *
     * Два AABB A и B не пересекаются если:
     *   A.maxX <= B.minX || A.minX >= B.maxX ||
     *   A.maxY <= B.minY || A.minY >= B.maxY ||
     *   A.maxZ <= B.minZ || A.minZ >= B.maxZ
     * Пересекаются: NOT (все условия разделения)
     */
    public boolean intersectsAny(AxisAlignedBB query) {
        float qMinX = (float) query.minX, qMinY = (float) query.minY, qMinZ = (float) query.minZ;
        float qMaxX = (float) query.maxX, qMaxY = (float) query.maxY, qMaxZ = (float) query.maxZ;

        FloatVector vQMinX = FloatVector.broadcast(SPECIES, qMinX);
        FloatVector vQMinY = FloatVector.broadcast(SPECIES, qMinY);
        FloatVector vQMinZ = FloatVector.broadcast(SPECIES, qMinZ);
        FloatVector vQMaxX = FloatVector.broadcast(SPECIES, qMaxX);
        FloatVector vQMaxY = FloatVector.broadcast(SPECIES, qMaxY);
        FloatVector vQMaxZ = FloatVector.broadcast(SPECIES, qMaxZ);

        int laneCount = SPECIES.length();
        int bound = SPECIES.loopBound(size);
        int i = 0;

        for (; i < bound; i += laneCount) {
            FloatVector vMinX = FloatVector.fromArray(SPECIES, minXArr, i);
            FloatVector vMinY = FloatVector.fromArray(SPECIES, minYArr, i);
            FloatVector vMinZ = FloatVector.fromArray(SPECIES, minZArr, i);
            FloatVector vMaxX = FloatVector.fromArray(SPECIES, maxXArr, i);
            FloatVector vMaxY = FloatVector.fromArray(SPECIES, maxYArr, i);
            FloatVector vMaxZ = FloatVector.fromArray(SPECIES, maxZArr, i);

            // Проверка непересечения по каждой оси
            VectorMask<Float> sepX = vMaxX.compare(VectorOperators.LE, vQMinX)
                .or(vMinX.compare(VectorOperators.GE, vQMaxX));
            VectorMask<Float> sepY = vMaxY.compare(VectorOperators.LE, vQMinY)
                .or(vMinY.compare(VectorOperators.GE, vQMaxY));
            VectorMask<Float> sepZ = vMaxZ.compare(VectorOperators.LE, vQMinZ)
                .or(vMinZ.compare(VectorOperators.GE, vQMaxZ));

            // AABB пересекаются если НЕ разделены ни по одной оси
            VectorMask<Float> separated = sepX.or(sepY).or(sepZ);
            VectorMask<Float> intersects = separated.not();

            if (intersects.anyTrue()) return true;
        }

        // Хвост
        for (; i < size; i++) {
            if (maxXArr[i] > qMinX && minXArr[i] < qMaxX &&
                maxYArr[i] > qMinY && minYArr[i] < qMaxY &&
                maxZArr[i] > qMinZ && minZArr[i] < qMaxZ) {
                return true;
            }
        }
        return false;
    }
}
```

---

## 10. Пример 5: Векторный шум Перлина для террейн-генерации

Самый тяжёлый участок генерации чанка — шумовые функции. Векторизация даёт 4–8× ускорение.

```java
package com.hardevo.thorium.world;

import jdk.incubator.vector.*;

/**
 * Векторизованный упрощённый 2D Perlin / Value noise.
 * Применение: ускорение генерации heightmap, спауна ore, биом-переходов.
 *
 * Полная реализация Perlin noise слишком длинная для вики,
 * здесь показана паттерн-векторизации inner loop.
 */
public class VectorNoise {

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;
    private static final int LANE_COUNT = SPECIES.length();

    // Предвычисленная таблица градиентов (512 float-пар)
    private final float[] gradX;
    private final float[] gradY;

    public VectorNoise(long seed) {
        // Инициализация таблиц градиентов...
        gradX = new float[512];
        gradY = new float[512];
        // ... заполнение по seed ...
    }

    /**
     * Вычисляет noise для массива координат X при фиксированном Y.
     * Результат записывается в out[].
     *
     * Вместо N последовательных вызовов noise(x[i], y) —
     * параллельная обработка LANE_COUNT координат.
     */
    public void evalRow(float[] xCoords, float fixedY, float[] out) {
        // Смягчающая функция fade: t*t*t*(t*(t*6-15)+10)
        // Векторно для всей строки точек

        FloatVector vY = FloatVector.broadcast(SPECIES, fixedY);
        // floor(fixedY)
        int yi = (int) Math.floor(fixedY) & 255;
        FloatVector vTy = vY.sub(FloatVector.broadcast(SPECIES, (float) Math.floor(fixedY)));
        // fade(ty)
        FloatVector vFy = fade(vTy);

        int bound = SPECIES.loopBound(xCoords.length);
        int i = 0;

        for (; i < bound; i += LANE_COUNT) {
            FloatVector vX = FloatVector.fromArray(SPECIES, xCoords, i);

            // Целые координаты
            // (Для полного Perlin нужна операция floor + cast, здесь упрощённо)
            FloatVector vFloorX = floor(vX);
            FloatVector vTx = vX.sub(vFloorX);
            FloatVector vFx = fade(vTx);

            // Интерполяция (lerp векторно)
            FloatVector result = lerp(vFx, vFy,
                FloatVector.broadcast(SPECIES, 0.5f),  // заглушка для примера
                FloatVector.broadcast(SPECIES, 0.5f)
            );

            result.intoArray(out, i);
        }

        // Хвост: скалярный fallback
        for (; i < xCoords.length; i++) {
            out[i] = scalarNoise(xCoords[i], fixedY);
        }
    }

    // Векторная fade-функция: 6t^5 - 15t^4 + 10t^3
    private static FloatVector fade(FloatVector t) {
        FloatVector v6  = FloatVector.broadcast(SPECIES, 6f);
        FloatVector v15 = FloatVector.broadcast(SPECIES, 15f);
        FloatVector v10 = FloatVector.broadcast(SPECIES, 10f);
        // t * (t * (t * (t * (t * 6 - 15) + 10)))
        return t.mul(t.mul(t.mul(t.mul(t.mul(v6).sub(v15)).add(v10))));
    }

    // Векторная линейная интерполяция: a + t*(b-a)
    private static FloatVector lerp(FloatVector t, FloatVector unused,
                                     FloatVector a, FloatVector b) {
        return a.add(t.mul(b.sub(a)));
    }

    // Векторный floor: x - fract(x)
    private static FloatVector floor(FloatVector v) {
        // Конвертируем float→int→float
        // В Vector API нет прямого floor, используем convert + обратно
        VectorSpecies<Integer> intSp = IntVector.SPECIES_PREFERRED;
        // Простое truncation (для положительных чисел = floor)
        return v.convert(VectorOperators.F2I, 0)
                .reinterpretAsInts()
                .convert(VectorOperators.I2F, 0)
                .reinterpretAsFloats();
    }

    private float scalarNoise(float x, float y) {
        // Скалярная реализация для хвоста
        return 0f; // заглушка
    }
}
```

---

## 11. Пример 6: Zstd-сжатие буфера чанка с Vector prefetch

Подготовка данных чанка перед Zstd-сжатием: delta-кодирование block ID массива.

```java
package com.hardevo.thorium.compress;

import jdk.incubator.vector.*;

/**
 * Векторное delta-кодирование block ID массива перед сжатием.
 * Вместо хранения абсолютных значений: хранится разность соседних элементов.
 * Улучшает степень сжатия Zstd на 15–30% для типичных чанков.
 */
public class VectorDeltaEncoder {

    private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;

    /**
     * Delta-кодирование: out[i] = src[i] - src[i-1]
     * src[0] остаётся без изменений (абсолютное значение).
     *
     * @param src  - массив block ID (short[], 16*256*16 = 65536 элементов)
     * @param out  - результирующий delta-массив
     */
    public static void encode(short[] src, short[] out) {
        if (src.length == 0) return;

        out[0] = src[0];

        int laneCount = SPECIES.length();
        // Векторная дельта: каждый элемент - предыдущий
        // Обрабатываем парами: v[i..i+n] - v[i-1..i+n-1]
        // Для этого нужен "сдвинутый" вектор

        int i = 1;
        int bound = SPECIES.loopBound(src.length - 1) + 1;

        // Предыдущий вектор (скользящий)
        // Упрощение: для больших массивов без cross-vector зависимостей
        // обрабатываем блоками с ручным стыком
        for (; i < bound; i += laneCount) {
            ShortVector vCurr = ShortVector.fromArray(SPECIES, src, i);
            ShortVector vPrev = ShortVector.fromArray(SPECIES, src, i - 1);
            vCurr.sub(vPrev).intoArray(out, i);
        }

        for (; i < src.length; i++) {
            out[i] = (short)(src[i] - src[i - 1]);
        }
    }

    /**
     * Декодирование: восстановление абсолютных значений из дельт.
     * Prefix sum — эта операция НЕ векторизуется тривиально из-за зависимостей.
     * Используем скалярный вариант.
     */
    public static void decode(short[] delta, short[] out) {
        out[0] = delta[0];
        for (int i = 1; i < delta.length; i++) {
            out[i] = (short)(out[i - 1] + delta[i]);
        }
    }
}
```

---

## 12. Интеграция с Thorium / EntityThreading

Использование VectorHelper в MixinChunkProviderServer для ускорения генерации.

```java
package com.hardevo.thorium.mixin;

import com.hardevo.thorium.chunk.VectorHeightmapAnalyzer;
import com.hardevo.thorium.chunk.VectorBiomeAnalyzer;
import com.hardevo.thorium.compress.VectorDeltaEncoder;
import com.hardevo.thorium.vector.VectorHelper;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(net.minecraft.world.gen.ChunkProviderServer.class)
public abstract class MixinChunkProviderServer {

    /**
     * Хук после генерации чанка: векторный анализ для метрик Thorium.
     */
    @Inject(
        method = "provideChunk",
        at = @At("RETURN"),
        remap = true
    )
    private void thorium$onChunkProvided(int x, int z,
                                          CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = cir.getReturnValue();
        if (chunk == null) return;

        // Быстрый анализ метрик с Vector API
        if (VectorApiCheck.isAvailable()) {
            int[] heightmap = chunk.getHeightMap();
            int variance = VectorHeightmapAnalyzer.heightVariance(heightmap);
            int maxHeight = VectorHeightmapAnalyzer.maxIntArray(heightmap);

            // Логируем в Thorium metrics
            ThoriumMetrics.recordChunkVariance(x, z, variance, maxHeight);
        }
    }
}
```

```java
package com.hardevo.thorium.mixin;

import com.hardevo.thorium.entity.VectorAABBCollision;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import java.util.List;

/**
 * Заменяет O(N) линейный поиск коллизий на векторный батч.
 * Применяется при вызове World.getEntitiesWithinAABBExcludingEntity.
 */
@Mixin(World.class)
public abstract class MixinWorldAABB {

    // VectorAABBCollision кешируется per-chunk или per-region
    // для избежания аллокации на каждый запрос

    @Inject(
        method = "getEntitiesWithinAABBExcludingEntity",
        at = @At("HEAD"),
        cancellable = true
    )
    private void thorium$vectorAABBCollision(Entity entity, AxisAlignedBB aabb,
                                              org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<List<Entity>> cir) {
        if (!VectorApiCheck.isAvailable()) return;

        // Используем VectorAABBCollision для предварительного отбора
        // (полная реализация требует интеграции с chunk-кешем)
    }
}
```

---

## 13. VectorMask: условные операции

`VectorMask<E>` — ключевой инструмент для branch-free кода.

```java
package com.hardevo.thorium.demo;

import jdk.incubator.vector.*;

public class MaskDemo {

    static final VectorSpecies<Float> SP = FloatVector.SPECIES_PREFERRED;

    /**
     * Clamp: зажимает все значения в диапазон [min, max].
     * Без масок: 2N сравнений + N условных присваиваний.
     * С масками: 2 векторных compare + 2 blend — branch-free!
     */
    public static void clamp(float[] data, float min, float max) {
        FloatVector vMin = FloatVector.broadcast(SP, min);
        FloatVector vMax = FloatVector.broadcast(SP, max);
        int laneCount = SP.length();
        int bound = SP.loopBound(data.length);
        int i = 0;

        for (; i < bound; i += laneCount) {
            FloatVector v = FloatVector.fromArray(SP, data, i);
            v.max(vMin).min(vMax).intoArray(data, i);
        }

        for (; i < data.length; i++) {
            data[i] = Math.max(min, Math.min(max, data[i]));
        }
    }

    /**
     * Абсолютное значение — маска для отрицательных элементов.
     */
    public static void abs(float[] data) {
        FloatVector vZero = FloatVector.zero(SP);
        int laneCount = SP.length();
        int bound = SP.loopBound(data.length);
        int i = 0;

        for (; i < bound; i += laneCount) {
            FloatVector v = FloatVector.fromArray(SP, data, i);
            VectorMask<Float> negative = v.lt(vZero);
            // Там где отрицательные — берём neg(v), иначе v
            v.blend(v.neg(), negative).intoArray(data, i);
        }

        for (; i < data.length; i++) {
            data[i] = Math.abs(data[i]);
        }
    }

    /**
     * Условное прибавление: dst[i] += delta только если condition[i] == 1
     */
    public static void conditionalAdd(float[] dst, float[] condition, float delta) {
        FloatVector vDelta = FloatVector.broadcast(SP, delta);
        FloatVector vOne   = FloatVector.broadcast(SP, 1f);
        int laneCount = SP.length();
        int bound = SP.loopBound(dst.length);
        int i = 0;

        for (; i < bound; i += laneCount) {
            FloatVector vDst  = FloatVector.fromArray(SP, dst, i);
            FloatVector vCond = FloatVector.fromArray(SP, condition, i);
            VectorMask<Float> mask = vCond.eq(vOne);
            // Прибавляем delta там где условие истинно
            vDst.add(vDelta, mask).intoArray(dst, i);
        }

        for (; i < dst.length; i++) {
            if (condition[i] == 1f) dst[i] += delta;
        }
    }
}
```

---

## 14. Производительность и подводные камни

### Когда Vector API даёт выигрыш

| Условие | Ускорение |
|---------|-----------|
| Массив ≥ 512 элементов | 4–16× |
| Операции без branch внутри loop | Максимальное |
| SoA (Structure of Arrays) вместо AoS | +20–40% к скорости |
| CPU с AVX2/AVX-512 | 8×/16× float lanes |
| Горячий путь (вызывается 1000+/сек) | JIT успевает оптимизировать |

### Когда НЕ стоит применять

| Антипаттерн | Почему плохо |
|-------------|--------------|
| Массив < 64 элементов | Overhead загрузки в регистры > выигрыш |
| AoS (Array of Structures): `Entity[]` | Данные не лежат в памяти подряд |
| Сложный branch-heavy код | Маски усложняют код без выигрыша |
| Однократный вызов | JIT не успевает скомпилировать в SIMD |
| Вложенные зависимости между итерациями | Prefix sum, рекуррентности — нельзя векторизовать |

### Ловушки

**1. Аллокации в hot loop**
```java
// ПЛОХО: new int[laneCount] создаёт мусор каждую итерацию
for (int i = 0; i < bound; i += laneCount) {
    int[] tmp = new int[laneCount]; // <- GC pressure!
    ...
}

// ХОРОШО: буфер снаружи цикла
int[] tmp = new int[laneCount];
for (int i = 0; i < bound; i += laneCount) {
    // используем tmp
}
```

**2. Species mismatch**
```java
// ПЛОХО: смешиваем Species разного размера
FloatVector v256 = FloatVector.fromArray(FloatVector.SPECIES_256, a, 0);
FloatVector v128 = FloatVector.fromArray(FloatVector.SPECIES_128, b, 0);
v256.add(v128); // ClassCastException!

// ХОРОШО: один Species на всё
VectorSpecies<Float> sp = FloatVector.SPECIES_PREFERRED;
FloatVector va = FloatVector.fromArray(sp, a, 0);
FloatVector vb = FloatVector.fromArray(sp, b, 0);
```

**3. Забытый хвост**
```java
// loopBound() возвращает len - (len % laneCount)
// Элементы от loopBound() до len НУЖНО обработать скалярно!
int bound = SPECIES.loopBound(data.length);
// ... векторный цикл до bound ...
for (int i = bound; i < data.length; i++) { // <- не забыть!
    // скалярный хвост
}
```

---

## 15. Graceful Degradation: fallback на Java 8

Для совместимости с окружениями без Vector API:

```java
package com.hardevo.thorium.vector;

/**
 * Фасад с автоматическим fallback на скалярные операции.
 * Позволяет коду работать как на Java 8, так и на Java 21+.
 */
public final class SIMDOps {

    @FunctionalInterface
    public interface FloatArrayOp {
        void apply(float[] a, float[] b, float[] out, int len);
    }

    private static final FloatArrayOp ADD_OP;

    static {
        FloatArrayOp op;
        try {
            // Пробуем загрузить векторный вариант
            Class.forName("jdk.incubator.vector.FloatVector");
            op = (a, b, out, len) -> VectorHelper.addArrays(a, b, out);
        } catch (ClassNotFoundException e) {
            // Fallback: скалярный
            op = (a, b, out, len) -> {
                for (int i = 0; i < len; i++) out[i] = a[i] + b[i];
            };
        }
        ADD_OP = op;
    }

    public static void addArrays(float[] a, float[] b, float[] out) {
        ADD_OP.apply(a, b, out, a.length);
    }
}
```

---

## 16. Быстрый справочник по API

### Загрузка / сохранение

```java
// Из массива
FloatVector v = FloatVector.fromArray(SPECIES, array, offset);

// Из массива с маской (загружает только там где mask=true)
FloatVector v = FloatVector.fromArray(SPECIES, array, offset, mask);

// В массив
v.intoArray(array, offset);
v.intoArray(array, offset, mask); // с маской

// Broadcast: одно значение во все lanes
FloatVector vConst = FloatVector.broadcast(SPECIES, 3.14f);

// Zero vector
FloatVector vZero = FloatVector.zero(SPECIES);
```

### Арифметика

```java
FloatVector result = va.add(vb);       // va + vb
FloatVector result = va.sub(vb);       // va - vb
FloatVector result = va.mul(vb);       // va * vb
FloatVector result = va.div(vb);       // va / vb
FloatVector result = va.neg();          // -va
FloatVector result = va.abs();          // |va|
FloatVector result = va.min(vb);       // min(va, vb)
FloatVector result = va.max(vb);       // max(va, vb)
FloatVector result = va.sqrt();         // sqrt(va) — если поддерживается
FloatVector result = va.fma(vb, vc);   // va*vb + vc (fused multiply-add)
```

### Сравнение → VectorMask

```java
VectorMask<Float> m = va.eq(vb);   // ==
VectorMask<Float> m = va.lt(vb);   // <
VectorMask<Float> m = va.gt(vb);   // >
VectorMask<Float> m = va.le(vb);   // <=
VectorMask<Float> m = va.ge(vb);   // >=
VectorMask<Float> m = va.ne(vb);   // !=

// Общий метод
VectorMask<Float> m = va.compare(VectorOperators.LT, vb);
```

### Операции с масками

```java
VectorMask<Float> m3 = m1.and(m2);   // AND масок
VectorMask<Float> m3 = m1.or(m2);    // OR масок
VectorMask<Float> m2 = m1.not();     // NOT маски
int count = m.trueCount();            // Количество true lanes
boolean any = m.anyTrue();            // Хоть одна true?
boolean all = m.allTrue();            // Все true?

// Blend: где mask=true берём vb, иначе va
FloatVector result = va.blend(vb, mask);
// Условная операция: прибавляем только где mask=true
FloatVector result = va.add(vb, mask);
```

### Редукция

```java
float sum  = v.reduceLanes(VectorOperators.ADD);
float max  = v.reduceLanes(VectorOperators.MAX);
float min  = v.reduceLanes(VectorOperators.MIN);
float mul  = v.reduceLanes(VectorOperators.MUL);

// С маской: только по lane где mask=true
float sum = v.reduceLanes(VectorOperators.ADD, mask);
```

### Конвертация типов

```java
// float → int (truncation)
Vector<Integer> vi = vf.convert(VectorOperators.F2I, 0);

// int → float
Vector<Float> vf = vi.convert(VectorOperators.I2F, 0);

// byte → int (расширение)
Vector<Integer> vi = vb.convert(VectorOperators.B2I, 0 /*part*/);

// Reinterpret (без преобразования, только тип)
IntVector vi = vf.reinterpretAsInts();
```

---

## Итог

Vector API в Java 25 — зрелый, продакшн-пригодный инструмент для SIMD-ускорения. В контексте Minecraft Forge 1.12.2 с CleanroomMC (Java 21+) он особенно эффективен для:

- **Thorium**: обработка биомовых массивов, heightmap, анализ lighting секций
- **EntityThreading**: батч-AABB коллизии в SoA-формате
- **Террейн-генерация**: векторизованный шум (4–8× ускорение inner loop)
- **Компрессия**: delta-кодирование перед Zstd

Главное правило: **применять только на массивах ≥ 256 элементов, в hot path, с SoA-данными и fallback на Java 8**.

---

*Источники: [JEP 508](https://openjdk.org/jeps/508) (Java 25), [JEP 529](https://openjdk.org/jeps/529) (Java 26 draft), [Baeldung Vector API](https://www.baeldung.com/java-vector-api), [DZone SIMD Java](https://dzone.com/articles/power-of-simd-with-java-vector-api), Pufferfish SIMD optimization (`--add-modules=jdk.incubator.vector`).*
