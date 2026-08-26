package tech.ryadom.jabbit.demo.android

import android.app.Application
import tech.ryadom.jabbit.demo.JabbitDemo
import tech.ryadom.jabbit.demo.createDemoJabbit

class DemoApplication : Application() {

    val jabbitDemo by lazy {
        JabbitDemo(jabbit = createDemoJabbit())
    }
}
