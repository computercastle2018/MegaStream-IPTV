using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace MegaStream.Server.Migrations
{
    /// <inheritdoc />
    public partial class AddProviderRedemptionCodes : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "ProviderRedemptionCodes",
                columns: table => new
                {
                    CodeHash = table.Column<string>(type: "varchar(64)", maxLength: 64, nullable: false)
                        .Annotation("MySql:CharSet", "utf8mb4"),
                    ProfileId = table.Column<Guid>(type: "char(36)", nullable: false, collation: "ascii_general_ci"),
                    ExpiresAt = table.Column<DateTime>(type: "datetime(6)", nullable: false),
                    RedeemedInstallationId = table.Column<Guid>(type: "char(36)", nullable: true, collation: "ascii_general_ci"),
                    RedeemedAt = table.Column<DateTime>(type: "datetime(6)", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_ProviderRedemptionCodes", x => x.CodeHash);
                    table.ForeignKey(
                        name: "FK_ProviderRedemptionCodes_Installations_RedeemedInstallationId",
                        column: x => x.RedeemedInstallationId,
                        principalTable: "Installations",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                    table.ForeignKey(
                        name: "FK_ProviderRedemptionCodes_RemoteProviderProfiles_ProfileId",
                        column: x => x.ProfileId,
                        principalTable: "RemoteProviderProfiles",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                })
                .Annotation("MySql:CharSet", "utf8mb4");

            migrationBuilder.CreateIndex(
                name: "IX_ProviderRedemptionCodes_ProfileId",
                table: "ProviderRedemptionCodes",
                column: "ProfileId");

            migrationBuilder.CreateIndex(
                name: "IX_ProviderRedemptionCodes_RedeemedInstallationId",
                table: "ProviderRedemptionCodes",
                column: "RedeemedInstallationId");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "ProviderRedemptionCodes");
        }
    }
}
