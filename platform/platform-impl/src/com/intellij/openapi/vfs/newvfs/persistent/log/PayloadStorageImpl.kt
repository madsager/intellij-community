// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs.persistent.log

import com.intellij.openapi.vfs.newvfs.persistent.log.PayloadRef.PayloadSource
import com.intellij.openapi.vfs.newvfs.persistent.log.io.AppendLogStorage
import com.intellij.openapi.vfs.newvfs.persistent.log.io.AppendLogStorage.Companion.Mode
import com.intellij.openapi.vfs.newvfs.persistent.log.timemachine.State
import com.intellij.util.io.DataInputOutputUtil
import com.intellij.util.io.DataOutputStream
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import java.io.*
import java.nio.file.Path

class PayloadStorageImpl(
  storagePath: Path,
) : PayloadStorage {
  override val sourcesDeclaration: PersistentSet<PayloadSource> = persistentSetOf(PayloadSource.PayloadStorage)
  private val appendLogStorage: AppendLogStorage = AppendLogStorage(storagePath, Mode.ReadWrite, CHUNK_SIZE)

  override fun appendPayload(sizeBytes: Long): PayloadStorageIO.PayloadAppendContext {
    require(sizeBytes >= 0)
    val serializedSizeBuf = ByteArrayOutputStream(10) // 1 + (64 - 6) / 7 < 10
    DataInputOutputUtil.writeLONG(DataOutputStream(serializedSizeBuf), sizeBytes)

    val serializedSize = serializedSizeBuf.toByteArray()
    val fullSize = serializedSize.size.toLong() + sizeBytes
    val appendContext = appendLogStorage.appendEntry(fullSize)

    return object : PayloadStorageIO.PayloadAppendContext {
      override fun fillData(data: ByteArray, offset: Int, length: Int): PayloadRef {
        appendContext.fillEntry {
          write(serializedSize)
          write(data, offset, length)
        }
        return PayloadRef(appendContext.position, PayloadSource.PayloadStorage)
      }

      override fun fillData(body: OutputStream.() -> Unit): PayloadRef {
        appendContext.fillEntry {
          write(serializedSize)
          body()
        }
        return PayloadRef(appendContext.position, PayloadSource.PayloadStorage)
      }

      override fun close() {
        appendContext.close()
      }
    }
  }

  override fun readPayload(payloadRef: PayloadRef): State.DefinedState<ByteArray> {
    if (payloadRef.source != PayloadSource.PayloadStorage) throw IllegalArgumentException("PayloadStorageImpl cannot read $payloadRef")
    if (payloadRef.offset >= appendLogStorage.size()) {
      return State.NotAvailable("failed to read $payloadRef: data was not written yet")
    }
    val buf = ByteArray(10) // 1 + (64 - 6) / 7 < 10
    appendLogStorage.read(payloadRef.offset, buf)
    val inp = ByteArrayInputStream(buf)
    val sizeBytes = try {
      DataInputOutputUtil.readLONG(DataInputStream(inp))
    }
    catch (e: IOException) {
      return State.NotAvailable("failed to read $payloadRef: size marker is malformed", e)
    }
    // hack: inp.available() = count - pos, so pos = count - inp.available() = buf.size - inp.available()
    val dataOffset = buf.size - inp.available()
    if (sizeBytes < 0) {
      return State.NotAvailable("failed to read $payloadRef: size marker is negative ($sizeBytes)")
    }
    val data = ByteArray(sizeBytes.toInt())
    appendLogStorage.read(payloadRef.offset + dataOffset, data)
    return data.let(State::Ready)
  }

  fun dropPayloadsUpTo(position: Long): Unit = appendLogStorage.clearUpTo(position)

  override fun size(): Long = appendLogStorage.size()

  override fun flush() {
    appendLogStorage.flush()
  }

  override fun dispose() {
    flush()
    appendLogStorage.close()
  }

  companion object {
    private const val MiB = 1024 * 1024
    private const val CHUNK_SIZE = 32 * MiB
  }
}