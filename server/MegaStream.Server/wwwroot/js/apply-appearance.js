document.querySelectorAll("select[data-apply-appearance]").forEach(select => {
    select.addEventListener("change", () => select.form?.requestSubmit());
});
