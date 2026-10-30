using System.Text;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace MegaStream.Server.Controllers;

[Route("admin/test-media")]
[Authorize(Policy = "Admin")]
[AutoValidateAntiforgeryToken]
public sealed class TestMediaController(IWebHostEnvironment environment) : Controller
{
    [HttpGet]
    [ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
    public IActionResult Index() => View();

    [AllowAnonymous]
    [HttpGet("/api/v1/test-media/video.mp4")]
    public IActionResult Video() => PhysicalFile(
        Path.Combine(environment.WebRootPath, "test-media", "sample.mp4"),
        "video/mp4", enableRangeProcessing: true);

    [AllowAnonymous]
    [HttpGet("/api/v1/test-media/playlist.m3u")]
    public IActionResult Playlist() => Content(
        "#EXTM3U\n#EXTINF:10,MegaStream synthetic test (10 seconds)\nhttps://megastrem.megastation.uk/api/v1/test-media/video.mp4\n",
        "audio/x-mpegurl", Encoding.UTF8);
}
