package com.example.daddybank

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import retrofit2.Call
import retrofit2.http.GET
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.Exception
import java.io.Serializable
import java.util.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

data class BankData(val users: List<User>)

data class User(val name: String, val transactions: List<Transaction>, val interestRates: List<InterestRate>)

data class Transaction(val date: String, val type: String, val amount: Double)

data class InterestRate(val startDate: String, val endDate: String, val rate: Double)

interface BankApi {
    @GET("files/dbstatefile.json")
    fun getBankData(): Call<BankData>
}

class BankDataRepository(context: Context) : Serializable {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("bank_data", Context.MODE_PRIVATE)

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://www.dankolab.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val bankApi: BankApi = retrofit.create(BankApi::class.java)

    var users: List<User> = listOf()
        private set

    suspend fun loadData() {
        Log.d("BankDataRepository", "loadData called")
        val cachedUsers = getCachedUsers()
        if (cachedUsers.isNotEmpty()) {
            users = cachedUsers
        } else {
            val result = fetchUsers()
            if (result.isSuccess) {
                users = result.getOrNull().orEmpty()
            }
        }
        Log.d("BankDataRepository", "loadData completed, users: $users")
    }

    suspend fun fetchUsers(): Result<List<User>> {
        return withContext(Dispatchers.IO) {
            try {
                val response = bankApi.getBankData().execute()
                if (response.isSuccessful) {
                    response.body()?.let { bankData ->
                        saveUsers(bankData.users)
                        Log.d("BankDataRepository", "Fetched and saved users: ${bankData.users}")
                        Result.success(bankData.users)
                    } ?: Result.failure(Exception("Invalid response"))
                } else {
                    Result.failure(Exception("Request failed"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }.also {
                Log.d("BankDataRepository", "fetchUsers result: $it")
            }
        }
    }

    fun getCachedUsers(): List<User> {
        val json = sharedPreferences.getString("users", "")
        if (json.isNullOrEmpty()) {
            return emptyList()
        }

        val type = object : TypeToken<List<User>>() {}.type
        return Gson().fromJson(json, type)
    }

    private fun saveUsers(users: List<User>) {
        val json = Gson().toJson(users)
        sharedPreferences.edit().putString("users", json).apply()
    }

    fun getAccountValuesSeries(user: User): List<Pair<String, Double>> {
        Log.d("BankDataRepository", "Starting BankDataRepository")
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        // Create a list of all events (transactions and interest rate changes), each with a date and an action
        val events = mutableListOf<Event>()

        // Add transactions to the list
        user.transactions.forEach { transaction ->
            val date = sdf.parse(transaction.date) ?: return@forEach
            val action: (Double) -> Double = if (transaction.type == "deposit") {
                { balance -> balance + transaction.amount }
            } else {
                { balance -> balance - transaction.amount }
            }
            events.add(Event(date, action, null))
        }

        // Add interest rate changes to the list
        user.interestRates.forEach { interestRate ->
            val startDate = sdf.parse(interestRate.startDate) ?: return@forEach
            val action: (Double) -> Double = { balance -> balance }
            events.add(Event(startDate, action, interestRate.rate))

            val endDate = sdf.parse(interestRate.endDate) ?: return@forEach
            val calendar = Calendar.getInstance()
            calendar.time = endDate
            calendar.add(Calendar.DATE, 1)
            events.add(Event(calendar.time, action, null))
        }

        // Sort the events by date
        events.sortBy { it.date }

        // Calculate the balance incrementally
        val accountValuesSeries = mutableListOf<Pair<String, Double>>()
        var balance = 0.0
        var currentInterestRate = 0.0
        val calendar = Calendar.getInstance()
        calendar.time = events.first().date
        for (i in 0 until events.size) {
            val event = events[i]
            while (calendar.time < event.date) {
                balance *= (1 + currentInterestRate / 365.0)
                accountValuesSeries.add(Pair(sdf.format(calendar.time), balance))
                calendar.add(Calendar.DATE, 1)
            }

            // Update the current balance (if required).
            balance = event.action(balance)

            // Update the current interest rate (if required).
            if (event.interestRate != null) {
                currentInterestRate = event.interestRate
            }

            // Log event details and current balance
            Log.d("BankDataRepository", "Processing event on ${sdf.format(event.date)}, Balance after event: $balance, Current Interest Rate: $currentInterestRate")
        }

        // Handle the final event
        val today = Calendar.getInstance().time
        while (calendar.time <= today) {
            balance *= (1 + currentInterestRate / 365.0)
            accountValuesSeries.add(Pair(sdf.format(calendar.time), balance))
            calendar.add(Calendar.DATE, 1)
        }

        Log.d("BankDataRepository", "Final Balance: $balance")

        return accountValuesSeries
    }

    fun getCurrentInterestRate(user: User): Double {
        Log.d("BankDataRepository", "Retrieving current interest rate for ${user.name}")
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = Calendar.getInstance().time

        // Initialize currentInterestRate as 0.0
        var currentInterestRate = 0.0

        // Iterate over interestRates of the user
        user.interestRates.forEach { interestRate ->
            val endDate = sdf.parse(interestRate.endDate) ?: return@forEach

            // If endDate of the current interest rate is before today, then update currentInterestRate
            if (endDate.before(today) || endDate.equals(today)) {
                currentInterestRate = interestRate.rate
            }
        }

        Log.d("BankDataRepository", "Current interest rate for ${user.name}: $currentInterestRate")
        return currentInterestRate
    }

    class Event(val date: Date, val action: (Double) -> Double, val interestRate: Double?)

}
