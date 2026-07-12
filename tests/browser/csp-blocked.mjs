const status = document.querySelector("#status");
const module = Uint8Array.from([0,97,115,109,1,0,0,0]);
try {
  await WebAssembly.compile(module);
  status.textContent = "wasm-unexpectedly-allowed";
  status.dataset.result = "failed";
} catch (error) {
  status.textContent = error instanceof WebAssembly.CompileError ? "wasm-blocked" : "unexpected-error";
  status.dataset.result = error instanceof WebAssembly.CompileError ? "passed" : "failed";
}
