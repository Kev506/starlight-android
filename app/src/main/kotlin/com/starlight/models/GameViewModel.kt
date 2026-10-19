package com.starlight.models

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.starlight.games.GameData
import com.starlight.games.GameDetails
import com.starlight.utils.AppComputerObject
import kotlinx.coroutines.launch

class GameViewModel(private val gameRepository: GameData) : ViewModel() {
    private val _gameDetails = MutableLiveData<GameDetails>()
    val gameDetails: LiveData<GameDetails> = _gameDetails

    fun loadGame(app: AppComputerObject) {

        viewModelScope.launch {
            // The ViewModel asks the repository for data.
            val data: GameDetails = gameRepository.getGameDetailsFromSource(app)
            _gameDetails.postValue(data)
        }
    }
}