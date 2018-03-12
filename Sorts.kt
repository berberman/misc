
import java.util.*

	fun <T : Comparable<T>> Array<T>.disorganize() {
		val random = Random()
		val n = this.size
		for (i in 0 until n) {
			val randomKey = random.nextInt(n - i)
			val temp = this[i]
			this[i] = this[randomKey]
			this[randomKey] = temp
		}
	}

	fun <T : Comparable<T>> Array<T>.monkeySort() {
		fun isSorted() = sortedArray().contentEquals(this)
		while (!isSorted())
    			disorganize()
	}
