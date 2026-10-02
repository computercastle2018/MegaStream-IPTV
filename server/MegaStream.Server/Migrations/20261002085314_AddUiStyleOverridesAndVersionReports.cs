using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace MegaStream.Server.Migrations
{
    /// <inheritdoc />
    public partial class AddUiStyleOverridesAndVersionReports : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "AppVersionName",
                table: "V1InstallationMetadata",
                type: "varchar(64)",
                maxLength: 64,
                nullable: true)
                .Annotation("MySql:CharSet", "utf8mb4");

            migrationBuilder.AddColumn<DateTime>(
                name: "VersionReportedAt",
                table: "V1InstallationMetadata",
                type: "datetime(6)",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "UiStyle",
                table: "Licenses",
                type: "varchar(7)",
                maxLength: 7,
                nullable: true)
                .Annotation("MySql:CharSet", "utf8mb4");

            migrationBuilder.AddColumn<string>(
                name: "UiStyle",
                table: "Installations",
                type: "varchar(7)",
                maxLength: 7,
                nullable: true)
                .Annotation("MySql:CharSet", "utf8mb4");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "AppVersionName",
                table: "V1InstallationMetadata");

            migrationBuilder.DropColumn(
                name: "VersionReportedAt",
                table: "V1InstallationMetadata");

            migrationBuilder.DropColumn(
                name: "UiStyle",
                table: "Licenses");

            migrationBuilder.DropColumn(
                name: "UiStyle",
                table: "Installations");
        }
    }
}
