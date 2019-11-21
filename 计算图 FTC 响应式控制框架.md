# 计算图 FTC 响应式控制框架

本项目基于 [数据流](https://github.com/MechDancer/dataflow-jvm) 模型对机器人系统进行响应式控制构筑，提高计算并行度；将机器人硬件设备与其结构控制器抽象为组件，提供 DSL 定义，并使用 [dependency](https://github.com/MechDancer/dependency) 作为依赖管理器。

## 概述

### 术语

* *动态域* —— 一个用于装载 *组件* 的特殊容器，所有依赖项会被保存在域中的一个哈希集合中。
* *组件* —— *动态域* 中存放的东西，例如全局资源和功能模块。每个*组件* 都应定义 `equals()` 和 `hashCode()`，以解决在*动态域* 中的冲突问题。
* *机器人组件* —— 具有 `init()` 和 `stop()` 生命周期的*组件*。
* *依赖者*  —— 一类特殊的*组件*。当有*组件* 装载到*动态域* 时，它会通过*依赖者* 的 `sync()` 传入，*依赖者* 由此可以选择存下它的引用，更新依赖关系。
* *设备*  —— 具有唯一名字的*机器人组件*。
* *机器人* —— 管理着所有*机器人组件* 生命周期的*动态域*。
* *数据块* —— 数据的入口节点和出口节点。有关数据流详细部分请见其文档。

### 输出驱动块

```kotlin
class OutputDriver<T>(private val transform: ((T) -> T?) = { it }) : DataBlock<T> by broadcast(), Closeable {


    private val timer = RestartableTimer()

    init {
        this linkTo {
             timer(100) {
            	this@OutputDriver post this@OutputDriver.receive()
        	}
        }
    }


    infix fun linkWithTransform(block: (T) -> Unit) = linkTo {
        transform(it)?.let(block)
    }

    override fun close() {
        timer.close()
    }
}
```

输出驱动块为具有定时发送能力的数据块。这个数据块将下一条链接连向了自己，即定时向自己推送到来的数据。数据块是一种广播节点，具有源的能力，连向输出驱动块的目标节点（订阅者）将会收到定时的数据推送。

### 效应器

效应器为具有输出能力的设备。通常情况下效应器意味着具有被控制而达成输出的能力，它们含有输出驱动块。下面是框架中所提供的效应器：

* 电机
* 舵机
* 连续舵机

先来看看电机是如何实现的：

```kotlin
class Motor(name: String, private val direction: Direction = Direction.FORWARD) :
    NamedComponent<Motor>(name), Device, PowerOutput {

    override val power = OutputDriver<Double> { raw ->
        raw.checkedValue(-1.0..1.0)?.let {
            it * direction.sign
        } ?: logger.warn("Invalid motor power value: $raw, from $name").run { null }
    }

    override fun toString(): String = "${javaClass.simpleName}[$name]"

    enum class Direction(val sign: Double) {
        FORWARD(1.0), REVERSED(-1.0)
    }

    override fun stop() {
        power.close()
    }

}
```

`power` 是上文中所提到的输出驱动块，使用者向该节点推送控制功率即可实现对电机控制。`NamedComponent` 是带有名字的组件抽象类，为设备接口提供实现。

### 传感器

与效应器相反，传感器是具有输入能力的设备。因此，它的节点并非数据块，而是源节点，只能作为数据的源被订阅。
