package com.retro99.cloudaccount.domain

interface CloudPendingChangesRepository {
    suspend fun count(cloudUserId: String): Int
}
