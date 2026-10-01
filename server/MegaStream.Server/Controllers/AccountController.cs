using MegaStream.Server.Models.Admin;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;

namespace MegaStream.Server.Controllers;

[AutoValidateAntiforgeryToken]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class AccountController(SignInManager<IdentityUser> signInManager) : Controller
{
    [AllowAnonymous, HttpGet]
    public IActionResult Login(string? returnUrl = null) => View(new LoginForm { ReturnUrl = Url.IsLocalUrl(returnUrl) ? returnUrl : null });

    [AllowAnonymous, HttpPost]
    public async Task<IActionResult> Login(LoginForm form)
    {
        if (!ModelState.IsValid) { form.Password = ""; ModelState.Remove(nameof(form.Password)); return View(form); }
        var result = await signInManager.PasswordSignInAsync(form.UserName, form.Password, isPersistent: false, lockoutOnFailure: true);
        if (result.Succeeded) return Url.IsLocalUrl(form.ReturnUrl) ? LocalRedirect(form.ReturnUrl!) : RedirectToAction("Index", "Admin");
        form.Password = "";
        ModelState.Remove(nameof(form.Password));
        ModelState.AddModelError("", "تعذر تسجيل الدخول. تحقق من بياناتك أو حاول لاحقاً");
        return View(form);
    }
    [Authorize(Policy = "Admin"), HttpPost]
    public async Task<IActionResult> Logout()
    {
        await signInManager.SignOutAsync();
        return RedirectToAction(nameof(Login));
    }
}
