package tech.ryadom.jabbit.internal

import tech.ryadom.jabbit.Jabbit
import tech.ryadom.jabbit.JabbitScope

internal expect fun createPlatformJabbit(jabbitScope: JabbitScope): Jabbit
