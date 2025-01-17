// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.s3.editor

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Bucket
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.aws.toolkits.jetbrains.core.explorer.refreshAwsTree
import software.aws.toolkits.jetbrains.services.s3.download
import software.aws.toolkits.jetbrains.services.s3.resources.S3Resources
import software.aws.toolkits.jetbrains.services.s3.upload
import software.aws.toolkits.jetbrains.utils.ApplicationThreadPoolScope
import software.aws.toolkits.jetbrains.utils.getCoroutineBgContext
import software.aws.toolkits.jetbrains.utils.getCoroutineUiContext
import software.aws.toolkits.jetbrains.utils.notifyError
import software.aws.toolkits.resources.message
import java.io.InputStream
import java.io.OutputStream
import java.net.URL

class S3VirtualBucket(val s3Bucket: Bucket, prefix: String, val client: S3Client, val project: Project) :
    LightVirtualFile(vfsName(s3Bucket, prefix)),
    CoroutineScope by ApplicationThreadPoolScope("S3VirtualBucket") {

    var prefix = prefix
        set(value) {
            val oldName = name
            field = value
            VirtualFileManager.getInstance().notifyPropertyChanged(this, PROP_NAME, oldName, name)
        }

    override fun isDirectory(): Boolean = false /* Unit tests refuse to open this in an editor if this is true */
    override fun isValid(): Boolean = true
    override fun isWritable(): Boolean = false
    override fun getName(): String = vfsName(s3Bucket, prefix)
    override fun getParent(): VirtualFile? = null
    override fun getPath(): String = super.getName()
    override fun toString(): String = super.getName()

    override fun equals(other: Any?): Boolean {
        if (other !is S3VirtualBucket) {
            return false
        }
        return s3Bucket.name() == (other as? S3VirtualBucket)?.s3Bucket?.name() && prefix == (other as? S3VirtualBucket)?.prefix
    }

    override fun hashCode(): Int = s3Bucket.name().hashCode() + prefix.hashCode()

    suspend fun newFolder(name: String) {
        withContext(getCoroutineBgContext()) {
            client.putObject({ it.bucket(s3Bucket.name()).key(name.trimEnd('/') + "/") }, RequestBody.empty())
        }
    }

    suspend fun listObjects(prefix: String, continuationToken: String?): ListObjectsV2Response =
        withContext(getCoroutineBgContext()) {
            client.listObjectsV2 {
                it.bucket(s3Bucket.name()).delimiter("/").prefix(prefix).maxKeys(MAX_ITEMS_TO_LOAD).continuationToken(continuationToken)
            }
        }

    suspend fun listObjectVersions(key: String, keyMarker: String?, versionIdMarker: String?): ListObjectVersionsResponse? =
        withContext(getCoroutineBgContext()) {
            client.listObjectVersions {
                it.bucket(s3Bucket.name()).prefix(key).delimiter("/").maxKeys(MAX_ITEMS_TO_LOAD).keyMarker(keyMarker).versionIdMarker(versionIdMarker)
            }
        }

    suspend fun deleteObjects(keys: List<String>) {
        withContext(getCoroutineBgContext()) {
            val keysToDelete = keys.map { ObjectIdentifier.builder().key(it).build() }
            client.deleteObjects { it.bucket(s3Bucket.name()).delete { del -> del.objects(keysToDelete) } }
        }
    }

    suspend fun renameObject(fromKey: String, toKey: String) {
        withContext(getCoroutineBgContext()) {
            client.copyObject { it.copySource("${s3Bucket.name()}/$fromKey").destinationBucket(s3Bucket.name()).destinationKey(toKey) }
            client.deleteObject { it.bucket(s3Bucket.name()).key(fromKey) }
        }
    }

    suspend fun upload(project: Project, source: InputStream, length: Long, key: String) {
        withContext(getCoroutineBgContext()) {
            client.upload(project, source, length, s3Bucket.name(), key).await()
        }
    }

    suspend fun download(project: Project, key: String, versionId: String? = null, output: OutputStream) {
        withContext(getCoroutineBgContext()) {
            client.download(project, s3Bucket.name(), key, versionId, output).await()
        }
    }

    fun generateUrl(key: String, versionId: String?): URL = client.utilities().getUrl {
        it.bucket(s3Bucket.name())
        it.key(key)
        it.versionId(versionId)
    }

    fun handleDeletedBucket() {
        notifyError(project = project, content = message("s3.open.viewer.bucket_does_not_exist", s3Bucket.name()))
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFiles.forEach {
            if (it is S3VirtualBucket && it.name == s3Bucket.name()) {
                runBlocking(getCoroutineUiContext()) {
                    fileEditorManager.closeFile(it)
                }
            }
        }
        project.refreshAwsTree(S3Resources.LIST_BUCKETS)
    }

    private companion object {
        const val MAX_ITEMS_TO_LOAD = 300

        fun vfsName(s3Bucket: Bucket, subroot: String): String = if (subroot.isBlank()) {
            s3Bucket.name()
        } else {
            "${s3Bucket.name()}/$subroot"
        }
    }
}
