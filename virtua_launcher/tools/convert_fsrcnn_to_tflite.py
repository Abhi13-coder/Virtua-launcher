"""
Step 1 of 2: export suxrobGM/fsrcnn's pretrained checkpoint to ONNX.
Step 2 (done by the CI workflow, not this script) is `onnx2tf -i ... -o ...`,
which converts ONNX -> TFLite directly and handles the NCHW->NHWC transpose
itself -- see .github/workflows/android-ci.yml's "Convert FSRCNN" step, which
runs both steps and bundles the result into assets/models/ before the APK
build. This script only exists as something you can also run by hand.

Tailored to the REAL repo (cloned and inspected directly, not guessed):
https://github.com/suxrobGM/fsrcnn

  src/fsrcnn/models/fsrcnn.py defines:
      class FSRCNN(nn.Module):
          def __init__(self, scale=4, d=56, s=12, m=4, in_ch=1, out_ch=1)
  pretrained/fsrcnn_div2k.ckpt ships in the repo (~300KB, already trained).

FSRCNN operates on the Y (luma) channel only (in_ch=out_ch=1) -- see
SuperResEstimator.kt, which does YCbCr conversion/recombination on the
Kotlin side.

d/s/m/scale above are the class DEFAULTS, but this script now reads the
real values out of the checkpoint's saved "args" when present, so it
doesn't matter if they don't match the defaults.

Manual usage (same steps CI runs):
    git clone https://github.com/suxrobGM/fsrcnn.git && cd fsrcnn
    pip install torch --index-url https://download.pytorch.org/whl/cpu
    PYTHONPATH=src python /path/to/convert_fsrcnn_to_tflite.py \
        --checkpoint pretrained/fsrcnn_div2k.ckpt --out fsrcnn_div2k.onnx --input-size 64
    pip install onnx2tf onnx onnxsim onnxruntime
    onnx2tf -i fsrcnn_div2k.onnx -o saved_model_fsrcnn
    # -> saved_model_fsrcnn/model_float32.tflite
"""

import argparse
import sys


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True, help="path to fsrcnn_div2k.ckpt")
    parser.add_argument("--out", default="fsrcnn.onnx")
    parser.add_argument("--input-size", type=int, default=64,
                         help="LR patch side the exported model expects (must match "
                              "SuperResEstimator.upscale's inputSize argument)")
    parser.add_argument("--scale", type=int, default=4)
    parser.add_argument("--d", type=int, default=56)
    parser.add_argument("--s", type=int, default=12)
    parser.add_argument("--m", type=int, default=4)
    args = parser.parse_args()

    try:
        import torch
    except ImportError:
        sys.exit(
            "torch not installed. Run:\n"
            "  pip install torch --index-url https://download.pytorch.org/whl/cpu"
        )

    # Imported from the real repo -- run this script with that repo's src/ on
    # PYTHONPATH (e.g. from inside a checkout of suxrobGM/fsrcnn).
    try:
        from fsrcnn.models import FSRCNN
    except ImportError:
        sys.exit(
            "Couldn't import fsrcnn.models.FSRCNN. Run this script from inside\n"
            "a checkout of https://github.com/suxrobGM/fsrcnn (so src/ is on\n"
            "PYTHONPATH), e.g.:\n"
            "  git clone https://github.com/suxrobGM/fsrcnn.git && cd fsrcnn\n"
            "  PYTHONPATH=src python /path/to/convert_fsrcnn_to_tflite.py ..."
        )

    state = torch.load(args.checkpoint, map_location="cpu")

    # fsrcnn_div2k.ckpt is a full training checkpoint:
    #   {"model": <state_dict>, "optim": ..., "sched": ..., "epoch": ..., "args": ...}
    # -- not a bare state_dict and not Lightning's "state_dict" key either.
    # Check all three shapes rather than assuming one.
    if isinstance(state, dict):
        if "model" in state and isinstance(state["model"], dict):
            state_dict = state["model"]
        elif "state_dict" in state and isinstance(state["state_dict"], dict):
            state_dict = state["state_dict"]
        else:
            state_dict = state
    else:
        state_dict = state

    # If the checkpoint recorded the training hyperparameters, use them
    # instead of the CLI defaults -- more reliable than guessing d/s/m/scale.
    ckpt_args = state.get("args") if isinstance(state, dict) else None
    if ckpt_args is not None:
        get = (lambda k, d: getattr(ckpt_args, k, d)) if not isinstance(ckpt_args, dict) \
            else (lambda k, d: ckpt_args.get(k, d))
        args.scale = get("scale", args.scale)
        args.d = get("d", args.d)
        args.s = get("s", args.s)
        args.m = get("m", args.m)
        print(f"Using hyperparameters from checkpoint's saved args: "
              f"scale={args.scale} d={args.d} s={args.s} m={args.m}")

    # Strip a leading "model." prefix if the ckpt was saved from a wrapper module.
    state_dict = {(k[6:] if k.startswith("model.") else k): v for k, v in state_dict.items()}

    model = FSRCNN(scale=args.scale, d=args.d, s=args.s, m=args.m, in_ch=1, out_ch=1)
    model.load_state_dict(state_dict)
    model.eval()

    # PyTorch is NCHW ([1,1,H,W]). onnx2tf transposes to NHWC during the
    # ONNX->TFLite step, so the .tflite SuperResEstimator.kt loads really is
    # [1,size,size,1] -- no NCHW/NHWC mismatch to worry about on the Kotlin side.
    dummy = torch.randn(1, 1, args.input_size, args.input_size)
    torch.onnx.export(
        model, dummy, args.out,
        input_names=["y_in"], output_names=["y_out"],
        opset_version=13,
        dynamic_axes=None,  # fixed input size keeps the TFLite conversion simple
    )
    print(f"Wrote {args.out} -- run onnx2tf on this next (see module docstring).")


if __name__ == "__main__":
    main()
