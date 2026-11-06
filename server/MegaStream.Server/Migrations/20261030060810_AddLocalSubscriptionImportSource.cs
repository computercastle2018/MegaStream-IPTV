using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace MegaStream.Server.Migrations
{
    /// <inheritdoc />
    public partial class AddLocalSubscriptionImportSource : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<Guid>(
                name: "SourceInstallationId",
                table: "RemoteProviderProfiles",
                type: "char(36)",
                nullable: true,
                collation: "ascii_general_ci");

            migrationBuilder.AddColumn<long>(
                name: "SourceLocalId",
                table: "RemoteProviderProfiles",
                type: "bigint",
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "IX_RemoteProviderProfiles_SourceInstallationId_SourceLocalId",
                table: "RemoteProviderProfiles",
                columns: new[] { "SourceInstallationId", "SourceLocalId" },
                unique: true);

            migrationBuilder.AddForeignKey(
                name: "FK_RemoteProviderProfiles_Installations_SourceInstallationId",
                table: "RemoteProviderProfiles",
                column: "SourceInstallationId",
                principalTable: "Installations",
                principalColumn: "Id",
                onDelete: ReferentialAction.Restrict);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "FK_RemoteProviderProfiles_Installations_SourceInstallationId",
                table: "RemoteProviderProfiles");

            migrationBuilder.DropIndex(
                name: "IX_RemoteProviderProfiles_SourceInstallationId_SourceLocalId",
                table: "RemoteProviderProfiles");

            migrationBuilder.DropColumn(
                name: "SourceInstallationId",
                table: "RemoteProviderProfiles");

            migrationBuilder.DropColumn(
                name: "SourceLocalId",
                table: "RemoteProviderProfiles");
        }
    }
}
