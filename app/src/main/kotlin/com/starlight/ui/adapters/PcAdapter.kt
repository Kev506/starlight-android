package com.starlight.ui.adapters

import androidx.compose.runtime.mutableStateListOf
import com.starlight.models.MainViewModel
import com.starlight.nvstream.http.ComputerDetails
import com.starlight.nvstream.http.PairingManager.PairState
import com.starlight.utils.ComputerObject
import java.util.Locale

class PcAdapter(var viewModel: MainViewModel) {

    init {
        computerObjects = ArrayList()
    }

    // Update the list of computers
    fun updateComputers(newList: List<ComputerObject>) {
        _computers.clear()
        _computers.addAll(newList)
        sortList()
    }

    // Sort the list of computers
    private fun sortList() {
        _computers.sortWith(Comparator.comparing { lhs: ComputerObject ->
            lhs.details?.name!!.lowercase(
                Locale.ROOT
            )
        })
    }

    // Remove computer
    fun removeComputer(computer: ComputerObject?) {
        computerObjects?.remove(computer)
        updateComputers(computerObjects!!)
    }

    // Add computer
    fun addComputer(computer: ComputerObject) {
        computerObjects?.add(computer)
        updateComputers(computerObjects!!)
    }

    // Update existing computer
    fun updateComputer(details: ComputerDetails) {
        getCount().let {
            for (i in 0..<it) {
                val computer = get(i)

                // Check if this is the same computer
                if (details.uuid.equals(computer.details!!.uuid)) {
                    _computers[i] = ComputerObject(details)
                    break
                }
            }
        }

        // Update the loader state
        if (details.state == ComputerDetails.State.UNKNOWN) {
            viewModel.setComputerLoaderValue(true)
        } else {
            viewModel.setComputerLoaderValue(false)
        }

        // Update the paired state
        if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairState.PAIRED) {
            viewModel.setIsPaired(false)
        } else {
            viewModel.setIsPaired(true)
        }

        // Update the offline state
        if (details.state == ComputerDetails.State.OFFLINE) {
            viewModel.setIsOffline(true)
        } else {
            viewModel.setIsOffline(false)
        }
    }

    // Get computer
    fun get(index: Int?): ComputerObject {
        return _computers[index!!]
    }

    // Get computer count
    fun getCount(): Int {
        return _computers.size
    }

    companion object {

        private val _computers = mutableStateListOf<ComputerObject>()
        val computers: List<ComputerObject> = _computers

        var computerObjects: MutableList<ComputerObject>? = null
    }
}