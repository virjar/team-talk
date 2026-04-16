package com.virjar.tk.util

/**
 * QR Code encoder - produces a boolean matrix (true = dark module).
 * Implements Byte encoding mode with Error Correction Level L.
 */
internal fun encodeQrMatrix(data: String): Array<BooleanArray> {
    val bytes = data.toByteArray(Charsets.UTF_8)

    // Determine minimum QR version
    val version = findMinVersion(bytes.size)
    val size = 17 + version * 4 // QR matrix size

    // Get error correction info
    val totalCodewords = getTotalCodewords(version)
    val ecCodewords = getEcCodewordsPerBlock(version)
    val numBlocks = getNumBlocks(version)
    val dataCodewords = totalCodewords - ecCodewords * numBlocks

    // Encode data
    val dataBits = encodeData(bytes, dataCodewords, version)

    // Add error correction
    val finalData = addErrorCorrection(dataBits, ecCodewords, numBlocks, dataCodewords)

    // Create matrix
    val matrix = Array(size) { BooleanArray(size) }
    val reserved = Array(size) { BooleanArray(size) }

    // Place patterns
    placeFinderPattern(matrix, reserved, 0, 0)
    placeFinderPattern(matrix, reserved, size - 7, 0)
    placeFinderPattern(matrix, reserved, 0, size - 7)

    // Alignment patterns
    val alignPositions = getAlignmentPositions(version)
    for (ax in alignPositions) {
        for (ay in alignPositions) {
            if (!reserved[ay][ax]) {
                placeAlignmentPattern(matrix, reserved, ax, ay)
            }
        }
    }

    // Timing patterns
    for (i in 8 until size - 8) {
        if (!reserved[6][i]) {
            matrix[6][i] = i % 2 == 0
            reserved[6][i] = true
        }
        if (!reserved[i][6]) {
            matrix[i][6] = i % 2 == 0
            reserved[i][6] = true
        }
    }

    // Dark module
    matrix[size - 8][8] = true
    reserved[size - 8][8] = true

    // Reserve format info areas
    reserveFormatArea(reserved, size)

    // Place data bits
    placeDataBits(matrix, reserved, finalData, size)

    // Apply mask (mask pattern 0: (row + col) % 2 == 0)
    applyMask(matrix, reserved, size, 0)

    // Place format info
    val formatBits = getFormatBits(0, 1) // mask=0, ecl=L(01)
    placeFormatBits(matrix, size, formatBits)

    return matrix
}

private fun findMinVersion(dataLength: Int): Int {
    // Byte mode, EC Level L capacity
    val capacities = intArrayOf(0, 17, 32, 53, 78, 106, 134, 154, 192, 230, 271)
    for (v in 1..10) {
        if (dataLength <= capacities[v]) return v
    }
    return 10 // fallback
}

private fun getTotalCodewords(version: Int): Int {
    val total = intArrayOf(0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346)
    return total[version]
}

private fun getEcCodewordsPerBlock(version: Int): Int {
    val ec = intArrayOf(0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18)
    return ec[version]
}

private fun getNumBlocks(version: Int): Int {
    val blocks = intArrayOf(0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2)
    return blocks[version]
}

private fun getAlignmentPositions(version: Int): IntArray {
    return when (version) {
        1 -> intArrayOf()
        2 -> intArrayOf(6, 18)
        3 -> intArrayOf(6, 22)
        4 -> intArrayOf(6, 26)
        5 -> intArrayOf(6, 30)
        6 -> intArrayOf(6, 34)
        7 -> intArrayOf(6, 22, 38)
        8 -> intArrayOf(6, 24, 42)
        9 -> intArrayOf(6, 26, 46)
        10 -> intArrayOf(6, 28, 50)
        else -> intArrayOf()
    }
}

private fun encodeData(bytes: ByteArray, dataCodewords: Int, version: Int): BitArray {
    val bits = BitArray()

    // Mode indicator: 0100 (Byte mode)
    bits.appendBits(4, 4)

    // Character count (8 bits for version 1-9, 16 bits for 10+)
    val countBits = if (version <= 9) 8 else 16
    bits.appendBits(bytes.size, countBits)

    // Data
    for (b in bytes) {
        bits.appendBits(b.toInt() and 0xFF, 8)
    }

    // Terminator
    val totalDataBits = dataCodewords * 8
    val terminatorLength = minOf(4, totalDataBits - bits.size)
    bits.appendBits(0, terminatorLength)

    // Pad to byte boundary
    while (bits.size % 8 != 0) {
        bits.appendBit(false)
    }

    // Pad codewords
    var padIndex = 0
    val padBytes = intArrayOf(0xEC, 0x11)
    while (bits.size < totalDataBits) {
        bits.appendBits(padBytes[padIndex % 2], 8)
        padIndex++
    }

    return bits
}

private fun addErrorCorrection(data: BitArray, ecCodewords: Int, numBlocks: Int, dataCodewords: Int): BitArray {
    val dataBytes = data.toByteArray()
    val blockDataSize = dataCodewords / numBlocks

    val blocks = mutableListOf<ByteArray>()
    for (i in 0 until numBlocks) {
        val start = i * blockDataSize
        val end = start + blockDataSize
        blocks.add(dataBytes.copyOfRange(start, end))
    }

    // Generate EC for each block
    val ecBlocks = blocks.map { block ->
        rsEncode(block, ecCodewords)
    }

    // Interleave
    val result = BitArray()
    val maxDataLen = blocks.maxOf { it.size }
    for (i in 0 until maxDataLen) {
        for (block in blocks) {
            if (i < block.size) {
                result.appendBits(block[i].toInt() and 0xFF, 8)
            }
        }
    }
    for (i in 0 until ecCodewords) {
        for (ec in ecBlocks) {
            if (i < ec.size) {
                result.appendBits(ec[i].toInt() and 0xFF, 8)
            }
        }
    }

    return result
}

/** Reed-Solomon encoding */
private fun rsEncode(data: ByteArray, ecCount: Int): ByteArray {
    val gen = rsGeneratorPoly(ecCount)
    val result = ByteArray(ecCount)
    for (b in data) {
        val factor = b.toInt() and 0xFF xor result[0].toInt() and 0xFF
        System.arraycopy(result, 1, result, 0, result.size - 1)
        result[result.size - 1] = 0
        for (i in result.indices) {
            result[i] = (result[i].toInt() xor gfMul(gen[i], factor)).toByte()
        }
    }
    return result
}

private fun rsGeneratorPoly(degree: Int): IntArray {
    var poly = intArrayOf(1)
    for (i in 0 until degree) {
        val newPoly = IntArray(poly.size + 1)
        for (j in poly.indices) {
            newPoly[j] = newPoly[j] xor poly[j]
            newPoly[j + 1] = newPoly[j + 1] xor gfMul(poly[j], gfPow(2, i))
        }
        poly = newPoly
    }
    return poly
}

// GF(256) arithmetic
private val GF_EXP by lazy {
    IntArray(512).also { arr ->
        arr[0] = 1
        for (i in 1 until 512) {
            val v = arr[i - 1] * 2
            arr[i] = if (v >= 256) v xor 0x11D else v
        }
    }
}

private val GF_LOG by lazy {
    IntArray(256).also { arr ->
        for (j in 0..254) {
            arr[GF_EXP[j]] = j
        }
    }
}

private fun gfMul(a: Int, b: Int): Int {
    if (a == 0 || b == 0) return 0
    return GF_EXP[(GF_LOG[a] + GF_LOG[b]) % 255]
}

private fun gfPow(a: Int, n: Int): Int {
    if (n == 0) return 1
    var result = a
    for (i in 1 until n) {
        result = gfMul(result, a)
    }
    return result
}

private fun placeFinderPattern(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, ox: Int, oy: Int) {
    for (dy in 0..6) {
        for (dx in 0..6) {
            val x = ox + dx
            val y = oy + dy
            if (x in matrix[0].indices && y in matrix.indices) {
                val dark = when {
                    dy == 0 || dy == 6 || dx == 0 || dx == 6 -> true
                    dy in 2..4 && dx in 2..4 -> true
                    else -> false
                }
                matrix[y][x] = dark
                reserved[y][x] = true
            }
        }
    }
}

private fun placeAlignmentPattern(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, cx: Int, cy: Int) {
    for (dy in -2..2) {
        for (dx in -2..2) {
            val x = cx + dx
            val y = cy + dy
            if (x in matrix[0].indices && y in matrix.indices) {
                val dark = kotlin.math.abs(dx) == 2 || kotlin.math.abs(dy) == 2 || (dx == 0 && dy == 0)
                matrix[y][x] = dark
                reserved[y][x] = true
            }
        }
    }
}

private fun reserveFormatArea(reserved: Array<BooleanArray>, size: Int) {
    for (i in 0..8) {
        reserved[8][i] = true
        reserved[i][8] = true
    }
    for (i in 0..7) {
        reserved[8][size - 1 - i] = true
    }
    for (i in 0..7) {
        reserved[size - 1 - i][8] = true
    }
}

private fun placeDataBits(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, data: BitArray, size: Int) {
    var bitIndex = 0
    var isUpward = true
    var col = size - 1

    while (col >= 0) {
        if (col == 6) col--

        for (row in 0 until size) {
            val actualRow = if (isUpward) size - 1 - row else row
            for (dx in 0..1) {
                val actualCol = col - dx
                if (actualCol < 0) continue
                if (reserved[actualRow][actualCol]) continue

                if (bitIndex < data.size) {
                    matrix[actualRow][actualCol] = data.getBit(bitIndex)
                    bitIndex++
                }
            }
        }

        isUpward = !isUpward
        col -= 2
    }
}

private fun applyMask(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, size: Int, maskPattern: Int) {
    for (y in 0 until size) {
        for (x in 0 until size) {
            if (reserved[y][x]) continue
            val invert = when (maskPattern) {
                0 -> (y + x) % 2 == 0
                else -> false
            }
            if (invert) matrix[y][x] = !matrix[y][x]
        }
    }
}

private fun getFormatBits(mask: Int, ecl: Int): Int {
    val data = (ecl shl 3) or mask
    var bits = data shl 10
    val generator = 0b10100110111
    for (i in 4 downTo 0) {
        if ((bits ushr (10 + i)) and 1 != 0) {
            bits = bits xor (generator shl i)
        }
    }
    return ((data shl 10) or bits) xor 0b101010000010010
}

private fun placeFormatBits(matrix: Array<BooleanArray>, size: Int, formatBits: Int) {
    val positions1 = listOf(
        Pair(8, 0), Pair(8, 1), Pair(8, 2), Pair(8, 3), Pair(8, 4), Pair(8, 5),
        Pair(8, 7), Pair(8, 8),
        Pair(7, 8), Pair(5, 8), Pair(4, 8), Pair(3, 8), Pair(2, 8), Pair(1, 8), Pair(0, 8),
    )
    for (i in positions1.indices) {
        val (x, y) = positions1[i]
        matrix[y][x] = (formatBits shr i and 1) != 0
    }

    val positions2 = listOf(
        Pair(size - 1, 8), Pair(size - 2, 8), Pair(size - 3, 8), Pair(size - 4, 8),
        Pair(size - 5, 8), Pair(size - 6, 8), Pair(size - 7, 8),
        Pair(8, size - 8), Pair(8, size - 7), Pair(8, size - 6), Pair(8, size - 5),
        Pair(8, size - 4), Pair(8, size - 3), Pair(8, size - 2), Pair(8, size - 1),
    )
    for (i in positions2.indices) {
        val (x, y) = positions2[i]
        matrix[y][x] = (formatBits shr i and 1) != 0
    }
}

private class BitArray {
    private val data = mutableListOf<Boolean>()
    val size: Int get() = data.size

    fun appendBit(bit: Boolean) { data.add(bit) }

    fun appendBits(value: Int, numBits: Int) {
        for (i in numBits - 1 downTo 0) {
            data.add((value shr i) and 1 != 0)
        }
    }

    fun getBit(index: Int): Boolean = data[index]

    fun toByteArray(): ByteArray {
        val result = ByteArray((data.size + 7) / 8)
        for (i in data.indices) {
            if (data[i]) {
                result[i / 8] = (result[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
        }
        return result
    }
}
