package io.github.tonbo2339.adblocker

/**
 * ドメイン名の集合。64bit ハッシュのソート済み配列で持つので、文字列の HashSet よりずっと省メモリ
 * (24 万件で約 2MB、HashSet だと 25MB 前後)。
 * 別のドメインがハッシュ衝突で「含まれる」と誤判定される確率は 1 回の照合あたり 約 件数 / 2^64 で、実用上無視できる。
 */
class DomainSet private constructor(private val hashes: LongArray) {

    val size: Int get() = hashes.size

    operator fun contains(domain: String): Boolean =
        hashes.isNotEmpty() && hashes.binarySearch(hash(domain, 0, domain.length)) >= 0

    /** name そのものか、その親ドメイン (ads.example.com なら example.com, com) のどれかを含むか。 */
    fun matchesSelfOrParent(name: String): Boolean {
        if (hashes.isEmpty()) return false
        val end = if (name.endsWith('.')) name.length - 1 else name.length
        var from = 0
        while (from < end) {
            if (hashes.binarySearch(hash(name, from, end)) >= 0) return true
            val dot = name.indexOf('.', from)
            if (dot < 0 || dot >= end) return false
            from = dot + 1
        }
        return false
    }

    class Builder {
        private var hashes = LongArray(1024)
        private var count = 0

        fun add(domain: String) {
            if (count == hashes.size) hashes = hashes.copyOf(count * 2)
            hashes[count++] = hash(domain, 0, domain.length)
        }

        fun build(): DomainSet {
            val sorted = hashes.copyOf(count).apply { sort() }
            // 重複を除く
            var n = 0
            for (h in sorted) if (n == 0 || sorted[n - 1] != h) sorted[n++] = h
            return DomainSet(sorted.copyOf(n))
        }
    }

    /** 保存用の中身 (ソート済みのハッシュ)。fromSorted() で戻せる。 */
    fun toArray(): LongArray = hashes.copyOf()

    companion object {
        val EMPTY = DomainSet(LongArray(0))

        /** toArray() で取り出した中身から作る。ソート済みで重複が無いこと (壊れていれば null)。 */
        fun fromSorted(hashes: LongArray): DomainSet? {
            for (i in 1 until hashes.size) if (hashes[i - 1] >= hashes[i]) return null
            return DomainSet(hashes)
        }

        /** FNV-1a (64bit)。部分文字列を作らずに親ドメインのハッシュを計算できるよう範囲を受け取る。 */
        private fun hash(s: String, from: Int, end: Int): Long {
            var h = -3750763034362895579L // 0xcbf29ce484222325
            for (i in from until end) {
                h = h xor s[i].code.toLong()
                h *= 1099511628211L // 0x100000001b3
            }
            return h
        }
    }
}
