/* Document Studio host policy. Native editing runs offline; no AI service is configured. */
(() => {
  const style = document.createElement('style');
  style.textContent = '#AIAssistant-tab-label, #AIAssistant-tab, [id*="aiassistant"], [id*="ai-assistant"] { display: none !important; }';
  document.head.appendChild(style);
  window.enableMacrosExecution = false;
  let attempts = 0;
  const timer = setInterval(() => {
    if (window.app && app.LOUtil) app.LOUtil.isAIAssistantAvailable = () => false;
    if (window.app && app.map && app.map.wopi) {
      app.map.wopi.DisableAISettings = true;
      app.map.isAIConfigured = false;
    }
    if (++attempts >= 30) clearInterval(timer);
  }, 200);
})();
