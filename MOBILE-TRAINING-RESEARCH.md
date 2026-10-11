# Mobile LLM Training Research

**Date:** Sep 2026
**Purpose:** Reference for future project — on-device fine-tuning on mobile GPUs

---

## The Problem

Soup (github.com/MakazhanAlpamys/Soup) is the best LLM fine-tuning CLI — one YAML config, layer streaming trains 8B on 4GB laptop GPU, QLoRA with bitsandbytes. But:

- `detect_device()` checks CUDA, MPS, MLX, CPU only
- No Vulkan/OpenCL/NPU backend
- PyTorch + bitsandbytes stack is desktop-oriented
- Training is the whole point — inference alone doesn't need Soup

---

## Bridge Projects

### QVAC Fabric (45 stars)
- **Repo:** github.com/tether-ai-research/qvac-fabric-llm-finetune
- **What:** llama.cpp fork with LoRA fine-tuning across Vulkan, Metal, CUDA
- **Mobile:** Android Adreno 800+, ARM Mali, iOS Metal
- **Features:** SFT, instruction-tuning, masked-loss, checkpoint resume, LR scheduling
- **Models:** Qwen3, Gemma3, BitNet verified
- **Key innovation:** Dynamic tiling for Adreno's 128MiB SSBO limit
- **Pre-built:** `qvac-android-adreno-arm64-v1.0.zip` (180MB)
- **Gap:** No layer streaming, DPO/ORPO/SimPO losses, or auto batch sizing

### MobileFineTuner (16 stars)
- **Repo:** github.com/Edge-Intelligence-Lab/MobileFineTuner
- **What:** Pure C++ mobile-native fine-tuning
- **Features:** LoRA/Full-FT, ZeRO-inspired sharding, energy-aware throttling, custom autograd
- **Models:** GPT-2, Gemma, Qwen
- **Performance:** GPT-2 Small: 4-6 hours/epoch on phone
- **Gap:** Smaller model support, no preference losses

### VKNN (3 stars)
- **Repo:** github.com/katolikov/vknn
- **What:** Vulkan inference engine for Android GPUs (inference only, no training)
- **Models:** Qwen2.5-Coder-0.5B, Llama-3.2-1B/8B, SmolVLM2
- **Key:** Every op runs on GPU, zero CPU fallbacks

---

## Alternative Approaches

### Skip Training Entirely
- Pre-compute LoRA adapters on desktop, load on phone
- No on-device learning, but simplest and fastest

### Device-Server Hybrid (PAE MobiLLM)
- Phone runs frozen backbone forward, sends activations to server
- Server trains side network, keeps labels private
- 5.25x faster convergence than naive split learning
- Tradeoff: needs network, privacy leak risk on activations

### Zeroth-Order / Forward-Only (MobiZO)
- No backprop — estimate gradients from multiple forward passes
- Uses ExecuTorch (Meta's inference engine) on Android NPU
- Tradeoff: 10-100x slower convergence, but no activation storage needed

### Memory-Efficient Backprop (MeSP)
- Manually derived backward passes exploiting LoRA's low-rank structure
- Recompute `h = xA` (tiny) instead of storing it (huge)
- Peak memory: **361MB → 136MB** for Qwen2.5-0.5B (62% reduction)
- Tradeoff: 28% more compute time

### Layout Optimization (FBLayout)
- Reorganize tensor layouts for mobile GPU texture memory architecture
- Unified R-Tile layout for forward/backward passes
- 2.2-5.7x speedup on Mali/Adreno GPUs
- Not a new algorithm — just makes existing training not suck on mobile

### Selective LoRA Paths (EdgeTune)
- GradCut removes unnecessary LoRA adapter paths based on importance
- Data-free "reuse or re-tune" decision for model updates
- 20% cost reduction from pruning, 70-80% total savings with reuse policy

---

## Performance Reality Check

| Model | Phone (per epoch) | Desktop RTX 4090 |
|-------|-------------------|-------------------|
| GPT-2 Small (124M) | 4-6 hours | Minutes |
| GPT-2 Medium (355M) | 10-14 hours | ~10 min |
| Gemma 270M | 8-12 hours | ~5 min |
| Gemma 1B | Days | ~30 min |

Desktop is ~100x faster for general-purpose training.

---

## When Mobile Training Makes Sense

- Privacy-critical data (medical, legal) that can't leave device
- Federated learning across many devices
- Offline/remote environments with no server access
- Edge-specific adaptation (keyboard prediction, voice assistant)

## When It Doesn't

- General-purpose fine-tuning (desktop is 100x faster)
- Real-time adaptation (too slow on mobile)
- Anything needing >1B parameters (OOM on most phones)
- Production training workflows

---

## Recommended Architecture

### Desktop/Cloud Path (pragmatic)
```
Desktop/Cloud
├── Training: Soup (best CLI) or QVAC Fabric
├── Quantization: llama.cpp quantize → GGUF
└── Export: GGUF + LoRA adapters

Phone (Termux + proot)
├── Inference: llama.cpp (Vulkan) on mobile GPU
├── Chat: OfflineLLM, AnvilAI, or custom app
└── Model storage: GGUF format, 1-4GB per model
```

### Privacy-First Path
```
Phone
├── Frozen backbone (inference): llama.cpp + Vulkan
├── LoRA adapter loading: Pre-computed on desktop
└── Optional: MobiZO for zeroth-order on-device adaptation

Desktop
├── Training: Full pipeline with Soup or QVAC
├── Export: GGUF + LoRA adapters
└── Push to phone via USB/network
```

### True On-Device Path (ambitious)
```
Phone (Termux + proot)
├── QVAC Fabric: LoRA fine-tuning via Vulkan
├── MobileFineTuner: Pure C++ training (small models)
├── MeSP: Memory-efficient backprop for larger models
└── FBLayout: Tensor layout optimization for mobile GPU

Limitations: GPT-2/Gemma scale only, hours per epoch
```

---

## Key Links

- **Soup:** github.com/MakazhanAlpamys/Soup
- **QVAC Fabric:** github.com/tether-ai-research/qvac-fabric-llm-finetune
- **MobileFineTuner:** github.com/Edge-Intelligence-Lab/MobileFineTuner
- **MobiZO:** github.com/leigao97/MobiZO
- **MeSP:** github.com/crinex/acl-mesp
- **EdgeTune:** dl.acm.org/doi/10.1145/3774906.3802769
- **FBLayout:** arxiv.org/html/2607.21624
- **PAE MobiLLM:** arxiv.org/html/2507.01216
- **VKNN:** github.com/katolikov/vknn
- **llama.cpp:** github.com/ggml-org/llama.cpp
