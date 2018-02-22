package cn.berberman.test

import java.text.DecimalFormat
import java.util.*
import kotlin.math.round

data class RedPackage(var money: Double, var amount: Int)

fun randomMoney(redPackage: RedPackage): Double {
	redPackage.amount.takeIf { it == 1 }?.let {
		it.dec()
		return round((redPackage.money * 100)) / 100
	}
	return ((Random().nextDouble() * redPackage.money / redPackage.amount * 2).takeUnless { it < 0.1 } ?: 0.1).let {
		redPackage.amount--
		redPackage.money -= it
		DecimalFormat("#.00").format(it).toDouble()
	}

}