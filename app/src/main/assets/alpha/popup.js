chrome.storage.local.get("jmbLicenseStatus", ({ jmbLicenseStatus: status }) => {
  const line = document.getElementById("statusLine");
  if (status === "approved") line.textContent = "Licensed ✓ — click below to show/hide the panel.";
  else if (status === "revoked") line.textContent = "License revoked — contact the seller.";
  else line.textContent = "Waiting for license approval from the seller.";
});

// Alpha Social app: no chrome.tabs messaging in GeckoView, so signal via storage.
document.getElementById("toggleBtn").addEventListener("click", () => {
  chrome.storage.local.set({ jmbTogglePanel: Date.now() });
});
