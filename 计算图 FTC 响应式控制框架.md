# 计算图 FTC 响应式控制框架

本项目基于 [数据流](https://github.com/MechDancer/dataflow-jvm) 模型对机器人系统进行响应式控制构筑，提高计算并行度；将机器人硬件设备与其结构控制器抽象为组件，提供 DSL 定义，并使用 [dependency](https://github.com/MechDancer/dependency) 作为依赖管理器。

## 用例



## 概述

### 术语

* *动态域* —— 一个用于装载 *组件* 的特殊容器，所有依赖项会被保存在域中的一个哈希集合中。
* *组件* —— *动态域* 中存放的东西，例如全局资源和功能模块。每个*组件* 都应定义 `equals()` 和 `hashCode()`，以解决在*动态域* 中的冲突问题。
* *机器人组件* —— 具有 `init()` 和 `stop()` 生命周期的*组件*。
* *依赖者*  —— 一类特殊的*组件*。当有*组件* 装载到*动态域* 时，它会通过*依赖者* 的 `sync()` 传入，*依赖者* 由此可以选择存下它的引用，更新依赖关系。
* *设备*  —— 具有唯一名字的*机器人组件*。
* *机器人* —— 管理着所有*机器人组件* 生命周期的*动态域*。
* *数据块* —— 数据的入口节点和出口节点。有关数据流详细部分请见其[文档](https://github.com/MechDancer/dataflow-jvm)。

### 计算图

本框架设计的核心思想是将机器人的控制细分为组件，并将过程转为类似于计算图的并行的数据流：

![https://github.com/MechDancer/docs.mechdancer.github.io/blob/master/_docs/doc_images/network.png?raw=true](网络)

数据将从传感器的数据源流入，经过中间的并行计算过程，得到的结果流入效应器的目标控制量。此外，使用者不再需要关注机器人系统的调度问题，底层代码将循环处理数据，到传感器的数据源后一切操作将会变为并行。

### 依赖管理

与 [mechdancerlib]( https://github.com/MechDancer/mechdancerlib ) 中的结构化定义硬件设备不同，机器人是一个动态域，将设备作为组件扁平化储存在其中。对应的机械结构可定义为依赖者，其装载进机器人时会获取需要设备的引用，从而实现与 mechdancerlib 类似的低耦合结构控制。

### 设备束

尽管设备在装配到机器人动态域时丢失了结构数据，为了更加合理地命名其配置文件，最好将设备隶属于的结构附于名字前。设备束为定义提供了 DSL 语言，对于机器人来说设备束不是必要的，它仅仅帮助用户解决设备的统一命名问题。在调用 `Robot.setupDeviceBundle()` 后设备束的设备会装配进机器人动态域。

### 预置

本库预置了两种常用的结构，它们依赖于机器人动态域中的硬件设备。

#### 电机x编码器

为了设计的合理性，框架将一个 FTC 电机根据功能与行为拆分为电机和编码器。在某些情况下，电机x编码器这种组合的存在为实现控制提供了便利，例如单电机闭位置环 —— 具有编码器的电机才可实现该控制。电机x编码器没有定义新的硬件设备，它们依靠 `DependencyManager` 在装载进机器人动态域时获取同名电机与编码器的引用，为它们这对组合提供新的功能。

#### 底盘

底盘同样是非常常用的结构，它们是几个电机的组合

## 实现细节

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

与效应器相反，传感器是具有输入能力的设备。因此，它的节点并非数据块，而是源节点，只能作为数据的源被订阅。此外，传感器还有 `update()` 函数，用于底层代码通知传感器新数据的到来；是否将数据传入数据流取决于传感器判定该数据是否与上一次的不同。下面是框架中所提供的传感器：

* 手柄
* 编码器
* 颜色传感器
* 触碰传感器
* 陀螺仪
* 电压传感器

与 mechdancerlib 相似，本框架将电机与编码器分开定义，尽管最终它们会绑定到同一硬件上，但从上层看编码器与电机的存在并没有关联。值得注意的是手柄也是传感器 —— 在 FTC 库中它们作为 `OpMode` 中的特殊成员，事实上它们与其他传感器无二，同时也应参与响应式控制流程。下面来看看编码器的实现：

```kotlin
class Encoder(name: String, cpr: Double = 360.0) : NamedComponent<Encoder>(name), Sensor<EncoderData> {
    private val value = AtomicReference(EncoderData(.0, .0))

    private val ratio = 2 * PI / cpr

    /** Current position */
    val position get() = value.get().position

    /** Current speed */
    val speed get() = value.get().speed

    override val updated: ISource<EncoderData> = broadcast()

    override fun update(new: EncoderData) {
        val transformed = EncoderData(position * ratio, speed * ratio)
        if (value.getAndSet(transformed) != transformed)
            updated post transformed
    }

    override fun toString(): String = "${javaClass.simpleName}[$name]"

}
```

`value` 是传感器的缓存值，外部调用 `update()` 会更新缓存值并向 `updated` 源节点推送新数据。多传感器可能共同工作，但它们的数据并不会在同一数据流中一起到来。因此，传感器还定义了一些 `getter` 直接获取之前的缓存。

### 机器人

本框架最初是用于 [Standalone TC](https://github.com/standalonetc) 的宿主端，所有效应器的控制量出口与传感器入口对接的均为 TCP 网络通信。现在上位机与下位机合并，同在手机上运行，相应入口与出口直接对接 `hardwareMap` 即可。机器人动态域负责了它承载硬件的对接任务。

```kotlin
open class Robot(deviceConfig: DeviceBundle.() -> Unit = {}) : DynamicScope(), Closeable {

    init {
        setupDeviceBundle(deviceConfig)
    }

    fun <T : Component> T.attach() = apply { this@Robot.setup(this) }

    fun update(gamepad1: com.qualcomm.robotcore.hardware.Gamepad, gamepad2: com.qualcomm.robotcore.hardware.Gamepad) {  ...  }


    /**
     * Master gamepad
     */
    val master = Gamepad(0)

    /**
     * Helper gamepad
     */
    val helper = Gamepad(1)

    /**
     * Voltage sensor of battery
     */
    val voltageSensor = VoltageSensor()

    /**
     * Robot loop period
     */
    @Volatile
    var period = 0L
        private set


    /**
     * Init the robot
     */
    fun init(hardwareMap: HardwareMap) {

        check(!initialized)

        val namedDevices = devices.associateBy { it.name }

        logger.info("Finding hardware devices")
        namedDevices.mapNotNull { (name, device) ->
            runCatching {
                logger.info("Attempting to find device $name")
                hardwareMap[name]
            }
                .onSuccess { logger.info("Found $name") }
                .onFailure { logger.warn("Failed to find $name") }
                .getOrNull()?.let { device to it }
        }.let { availableDevices.putAll(it) }

        //Setup devices
        setupAvailableDevices()

        //Call init
        components.mapNotNull { it as? RobotComponent }.forEach(RobotComponent::init)

        //Initialize sensor accessors
        initSensors()

        //Link outputs
        linkOutputs()

        logger.info("Initialized")
        initialized = true
    }

    private fun linkOutputs() {  ...  }

    private fun initSensors() {  ...  }

    private fun setupAvailableDevices() {  ...  }

    override fun close() {  ...  }

}
```

在 `init()` 时机器人尝试将设备束中定义的设备与 `hardwareMap` 绑定，绑定成功才会装载进动态域中，被其他机器人组件依赖。绑定完成后机器人会将效应器的控制节点链接向 FTC 库的对应设备，并调用机器人组件相应生命周期回调。传感器数据则通过在 `OpMode` 中调用 `update()` 手动更新。
