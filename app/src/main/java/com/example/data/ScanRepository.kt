package com.example.data

import kotlinx.coroutines.flow.Flow

class ScanRepository(private val scanDao: ScanDao) {
    val allScans: Flow<List<ScanHistory>> = scanDao.getAllScans()

    suspend fun insert(scan: ScanHistory): Long {
        return scanDao.insertScan(scan)
    }

    suspend fun deleteById(id: Long) {
        scanDao.deleteScanById(id)
    }

    suspend fun clearAll() {
        scanDao.clearAll()
    }
}
