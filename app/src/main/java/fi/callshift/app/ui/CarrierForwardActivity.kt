package fi.callshift.app.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fi.callshift.app.CallShiftApp
import fi.callshift.app.databinding.ActivityCarrierBinding
import fi.callshift.app.domain.ForwardResult
import fi.callshift.app.telecom.MmiCodes
import kotlinx.coroutines.launch

class CarrierForwardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCarrierBinding
    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }

    private var simAccounts: List<Pair<String, String>> = emptyList()
    private val mmiServices = MmiCodes.ALL_SERVICES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCarrierBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        setupListeners()
    }

    private fun setupSpinners() {
        val accountsMap = app.telecom.phoneAccounts()
        simAccounts = if (accountsMap.isEmpty()) {
            listOf("ANY" to "Любая / По умолчанию")
        } else {
            accountsMap.toList()
        }

        val simLabels = simAccounts.map { it.second }
        binding.spinnerSim.adapter = darkSpinnerAdapter(this, simLabels)

        val mmiLabels = mmiServices.map { "${it.code} — ${it.labelRu}" }
        binding.spinnerMmiCode.adapter = darkSpinnerAdapter(this, mmiLabels)
    }

    private fun setupListeners() {
        binding.btnSet.setOnClickListener {
            val target = binding.etTarget.text.toString().trim()
            if (target.isEmpty()) {
                Toast.makeText(this, "Введите целевой номер", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val normTarget = app.normalizer.normalize(target).e164 ?: target
            val service = mmiServices[binding.spinnerMmiCode.selectedItemPosition]
            val mmi = MmiCodes.register(service.code, normTarget)
            executeMmi(mmi)
        }

        binding.btnQuery.setOnClickListener {
            val service = mmiServices[binding.spinnerMmiCode.selectedItemPosition]
            val mmi = MmiCodes.interrogate(service.code)
            executeMmi(mmi)
        }

        binding.btnCancelAll.setOnClickListener {
            executeMmi("##002#")
        }
    }

    private fun executeMmi(mmi: String) {
        val simIndex = binding.spinnerSim.selectedItemPosition
        val simId = simAccounts.getOrNull(simIndex)?.first?.takeIf { it != "ANY" }

        binding.tvUssdResponse.text = "Отправка $mmi..."

        lifecycleScope.launch {
            val result = app.telecom.placeMmi(simId, mmi)
            val text = when (result) {
                is ForwardResult.Ok -> result.detail
                is ForwardResult.Failed -> "Ошибка (${result.code}): ${result.message}"
                else -> result.toString()
            }
            binding.tvUssdResponse.text = text
        }
    }
}
